package io.dispatchgrid.worker;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventRouter {
  private final JdbcTemplate jdbc;

  public EventRouter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public void route(UUID eventId) {
    jdbc.update(
        """
            INSERT INTO webhook_deliveries(id,tenant_id,event_id,endpoint_id)
            SELECT gen_random_uuid(), e.tenant_id, e.id, w.id FROM events e
            JOIN webhook_endpoints w ON w.tenant_id=e.tenant_id AND w.active AND w.deleted_at IS NULL
            WHERE e.id=? AND EXISTS (SELECT 1 FROM webhook_subscriptions s WHERE s.endpoint_id=w.id AND (s.event_type=e.event_type OR s.event_type='*'))
            ON CONFLICT(event_id,endpoint_id) DO NOTHING
            """,
        eventId);
  }
}
