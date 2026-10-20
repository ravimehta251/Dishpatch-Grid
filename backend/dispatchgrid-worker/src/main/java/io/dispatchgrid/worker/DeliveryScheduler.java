package io.dispatchgrid.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryScheduler {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final DeadLetters deadLetters;

  public DeliveryScheduler(JdbcTemplate jdbc, ObjectMapper mapper, DeadLetters deadLetters) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.deadLetters = deadLetters;
  }

  @Transactional
  public void scheduleBatch() {
    recoverAbandoned();
    var due =
        jdbc.queryForList(
            """
            SELECT d.id,d.endpoint_id FROM webhook_deliveries d
            WHERE d.status IN ('PENDING','RETRY_SCHEDULED') AND d.next_attempt_at <= now()
            AND (d.lease_until IS NULL OR d.lease_until < now())
            ORDER BY d.next_attempt_at FOR UPDATE SKIP LOCKED LIMIT 50
            """);
    for (var job : due) {
      UUID id = (UUID) job.get("id");
      UUID lease = UUID.randomUUID();
      jdbc.update(
          "UPDATE webhook_deliveries SET lease_token=?,lease_until=now()+interval '60 seconds',updated_at=now() WHERE id=?",
          lease,
          id);
      try {
        jdbc.update(
            "INSERT INTO outbox_messages(id,topic,message_key,payload) VALUES (?,?,?,?)",
            UUID.randomUUID(),
            "dispatchgrid.delivery.jobs",
            job.get("endpoint_id").toString(),
            mapper.writeValueAsString(Map.of("deliveryId", id, "leaseToken", lease)));
      } catch (JsonProcessingException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private void recoverAbandoned() {
    var abandoned =
        jdbc.queryForList(
            """
            SELECT d.*,w.max_attempts FROM webhook_deliveries d JOIN webhook_endpoints w ON w.id=d.endpoint_id
            WHERE d.status='PROCESSING' AND d.lease_until < now() FOR UPDATE OF d SKIP LOCKED LIMIT 50
            """);
    for (var row : abandoned) {
      UUID id = (UUID) row.get("id"), tenant = (UUID) row.get("tenant_id");
      int attempt = ((Number) row.get("attempt_count")).intValue(),
          replay = ((Number) row.get("replay_count")).intValue();
      String next =
          attempt >= ((Number) row.get("max_attempts")).intValue()
              ? "EXHAUSTED"
              : "RETRY_SCHEDULED";
      jdbc.update(
          """
                INSERT INTO delivery_attempts(id,tenant_id,delivery_id,attempt_number,replay_number,latency_ms,outcome,error,started_at)
                VALUES (?,?,?,?,?,0,'ABANDONED','Worker lease expired; receiver outcome unknown',?) ON CONFLICT DO NOTHING
                """,
          UUID.randomUUID(),
          tenant,
          id,
          attempt,
          replay,
          row.get("updated_at"));
      jdbc.update(
          "UPDATE webhook_deliveries SET status=?,last_error='Worker lease expired; receiver outcome unknown',lease_token=NULL,lease_until=NULL,next_attempt_at=now(),updated_at=now() WHERE id=?",
          next,
          id);
      if (next.equals("EXHAUSTED"))
        deadLetters.record(tenant, id, replay, "Worker lease expired at maximum attempts");
    }
  }
}
