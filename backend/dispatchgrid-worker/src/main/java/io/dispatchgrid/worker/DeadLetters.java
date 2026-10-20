package io.dispatchgrid.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Caller must hold the delivery row lock and transaction. */
@Component
public class DeadLetters {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public DeadLetters(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public void record(UUID tenantId, UUID deliveryId, int replay, String reason) {
    int inserted =
        jdbc.update(
            "INSERT INTO dead_letters(id,tenant_id,delivery_id,replay_number,reason) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
            UUID.randomUUID(),
            tenantId,
            deliveryId,
            replay,
            reason);
    if (inserted > 0) {
      try {
        jdbc.update(
            "INSERT INTO outbox_messages(id,topic,message_key,payload) VALUES (?,?,?,?)",
            UUID.randomUUID(),
            "dispatchgrid.delivery.dlq",
            deliveryId.toString(),
            mapper.writeValueAsString(
                Map.of(
                    "deliveryId",
                    deliveryId,
                    "tenantId",
                    tenantId,
                    "replayNumber",
                    replay,
                    "reason",
                    reason)));
      } catch (JsonProcessingException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
