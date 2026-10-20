package io.dispatchgrid.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded housekeeping only; event and audit retention requires an explicit operator policy. */
@Component
@ConditionalOnProperty(
    name = "dispatchgrid.scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class Retention {
  private final JdbcTemplate db;

  public Retention(JdbcTemplate db) {
    this.db = db;
  }

  @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
  public void cleanup() {
    db.update(
        "DELETE FROM outbox_messages WHERE id IN (SELECT id FROM outbox_messages WHERE published_at<now()-interval '7 days' LIMIT 1000)");
    db.update(
        "DELETE FROM refresh_tokens WHERE id IN (SELECT id FROM refresh_tokens WHERE expires_at<now()-interval '7 days' LIMIT 1000)");
    db.update(
        "DELETE FROM idempotency_records WHERE (tenant_id,idempotency_key) IN (SELECT tenant_id,idempotency_key FROM idempotency_records WHERE expires_at<now() LIMIT 1000)");
    db.update(
        "UPDATE webhook_endpoints SET previous_secret=NULL,previous_secret_expires_at=NULL WHERE id IN (SELECT id FROM webhook_endpoints WHERE previous_secret_expires_at<now() LIMIT 1000)");
  }
}
