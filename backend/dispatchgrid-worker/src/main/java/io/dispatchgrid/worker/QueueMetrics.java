package io.dispatchgrid.worker;

import io.micrometer.core.instrument.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class QueueMetrics {
  public QueueMetrics(MeterRegistry metrics, JdbcTemplate db) {
    Gauge.builder(
            "dispatchgrid.outbox.pending",
            db,
            j ->
                j.queryForObject(
                    "SELECT count(*) FROM outbox_messages WHERE published_at IS NULL", Long.class))
        .register(metrics);
    Gauge.builder(
            "dispatchgrid.delivery.pending",
            db,
            j ->
                j.queryForObject(
                    "SELECT count(*) FROM webhook_deliveries WHERE status IN ('PENDING','PROCESSING','RETRY_SCHEDULED')",
                    Long.class))
        .register(metrics);
  }
}
