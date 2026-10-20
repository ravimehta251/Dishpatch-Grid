package io.dispatchgrid.worker;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OutboxPublisher {
  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final KafkaTemplate<String, String> kafka;
  private final MeterRegistry metrics;

  public OutboxPublisher(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      KafkaTemplate<String, String> kafka,
      MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.kafka = kafka;
    this.metrics = metrics;
  }

  record Message(UUID id, String topic, String key, String payload) {}

  public void publishBatch() {
    UUID lease = UUID.randomUUID();
    var batch =
        tx.execute(
            status ->
                jdbc.query(
                    """
            WITH candidates AS (
                SELECT id FROM outbox_messages WHERE published_at IS NULL AND (lease_until IS NULL OR lease_until < now())
                ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 5
            ) UPDATE outbox_messages o SET lease_token=?,lease_until=now()+interval '60 seconds',attempts=attempts+1
            FROM candidates c WHERE o.id=c.id RETURNING o.id,o.topic,o.message_key,o.payload
            """,
                    (rs, n) ->
                        new Message(
                            rs.getObject("id", UUID.class),
                            rs.getString("topic"),
                            rs.getString("message_key"),
                            rs.getString("payload")),
                    lease));
    if (batch == null) return;
    for (Message message : batch) {
      try {
        kafka.send(message.topic(), message.key(), message.payload()).get(7, TimeUnit.SECONDS);
        jdbc.update(
            "UPDATE outbox_messages SET published_at=now(),lease_token=NULL,lease_until=NULL,last_error=NULL WHERE id=? AND lease_token=?",
            message.id(),
            lease);
        metrics.counter("dispatchgrid.outbox.published").increment();
      } catch (Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        // A timed-out broker acknowledgement may still have committed: retrying is deliberately at
        // least once.
        jdbc.update(
            "UPDATE outbox_messages SET lease_until=now()+interval '5 seconds',last_error=? WHERE id=? AND lease_token=?",
            e.getClass().getSimpleName(),
            message.id(),
            lease);
        metrics.counter("dispatchgrid.outbox.failures").increment();
        log.warn(
            "outboxPublishFailed outboxId={} reason={}",
            message.id(),
            e.getClass().getSimpleName());
        if (Thread.currentThread().isInterrupted()) return;
      }
    }
  }
}
