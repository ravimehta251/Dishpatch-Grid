package io.dispatchgrid.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaListeners {
  private final ObjectMapper mapper;
  private final EventRouter router;
  private final DeliveryExecutor executor;
  private final JdbcTemplate db;

  public KafkaListeners(
      ObjectMapper mapper, EventRouter router, DeliveryExecutor executor, JdbcTemplate db) {
    this.mapper = mapper;
    this.router = router;
    this.executor = executor;
    this.db = db;
  }

  @KafkaListener(
      topics = "dispatchgrid.events",
      groupId = "dispatchgrid-router-v1",
      concurrency = "2",
      autoStartup = "${dispatchgrid.listeners-enabled:true}")
  public void event(String message) {
    UUID id;
    try {
      id = UUID.fromString(mapper.readTree(message).path("eventId").asText());
    } catch (Exception e) {
      quarantine("invalid-event-envelope");
      return;
    }
    router.route(id);
  }

  @KafkaListener(
      topics = "dispatchgrid.delivery.jobs",
      groupId = "dispatchgrid-delivery-v1",
      concurrency = "${dispatchgrid.concurrency:8}",
      autoStartup = "${dispatchgrid.listeners-enabled:true}")
  public void job(String message) {
    UUID id, token;
    try {
      var node = mapper.readTree(message);
      id = UUID.fromString(node.path("deliveryId").asText());
      token = UUID.fromString(node.path("leaseToken").asText());
    } catch (Exception e) {
      quarantine("invalid-job-envelope");
      return;
    }
    executor.execute(id, token);
  }

  private void quarantine(String reason) {
    db.update(
        "INSERT INTO outbox_messages(id,topic,message_key,payload) VALUES(?,'dispatchgrid.delivery.dlq',?,?)",
        UUID.randomUUID(),
        reason,
        "{\"reason\":\"" + reason + "\"}");
  }
}
