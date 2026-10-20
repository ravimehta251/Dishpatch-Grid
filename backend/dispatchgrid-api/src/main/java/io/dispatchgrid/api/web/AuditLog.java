package io.dispatchgrid.api.web;

import io.dispatchgrid.api.security.TenantPrincipal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AuditLog {
  private final JdbcTemplate jdbc;

  public AuditLog(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void record(TenantPrincipal actor, String action, UUID resource) {
    jdbc.update(
        "INSERT INTO audit_logs(id,tenant_id,actor_id,action,resource_id) VALUES (?,?,?,?,?)",
        UUID.randomUUID(),
        actor.tenantId(),
        actor.userId(),
        action,
        resource);
  }
}
