package io.dispatchgrid.api.endpoints;

import io.dispatchgrid.api.security.TenantPrincipal;
import io.dispatchgrid.api.web.*;
import io.dispatchgrid.common.*;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EndpointService {
  private final JdbcTemplate db;
  private final SqlViews views;
  private final CryptoService crypto;
  private final DestinationPolicy policy;
  private final AuditLog audit;

  public EndpointService(
      JdbcTemplate db,
      SqlViews views,
      CryptoService crypto,
      DestinationPolicy policy,
      AuditLog audit) {
    this.db = db;
    this.views = views;
    this.crypto = crypto;
    this.policy = policy;
    this.audit = audit;
  }

  static final String SELECT =
      "SELECT id,url,description,active,max_attempts,retry_base_seconds,retry_max_seconds,created_at,updated_at,ARRAY(SELECT event_type FROM webhook_subscriptions s WHERE s.endpoint_id=w.id ORDER BY event_type) AS subscribed_events FROM webhook_endpoints w";

  public Map<String, Object> get(UUID tenant, UUID id) {
    return db
        .query(SELECT + " WHERE tenant_id=? AND id=? AND deleted_at IS NULL", views, tenant, id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(404, "Endpoint not found"));
  }

  public PageResult<?> list(UUID tenant, int page, int size) {
    int offset = PageResult.offset(page, size);
    return new PageResult<>(
        db.query(
            SELECT
                + " WHERE tenant_id=? AND deleted_at IS NULL ORDER BY created_at DESC LIMIT ? OFFSET ?",
            views,
            tenant,
            size,
            offset),
        page,
        size,
        db.queryForObject(
            "SELECT count(*) FROM webhook_endpoints WHERE tenant_id=? AND deleted_at IS NULL",
            Long.class,
            tenant));
  }

  @Transactional
  public Map<String, Object> save(TenantPrincipal p, UUID id, Input input) {
    p.requireDeveloper();
    policy.validate(input.url());
    if (input.retryMaxSeconds() < input.retryBaseSeconds())
      throw new ApiException(400, "Retry maximum must exceed base");
    boolean create = id == null;
    String secret = null;
    if (create) {
      id = UUID.randomUUID();
      secret = crypto.randomToken();
      db.update(
          "INSERT INTO webhook_endpoints(id,tenant_id,url,description,active,signing_secret,max_attempts,retry_base_seconds,retry_max_seconds) VALUES(?,?,?,?,?,?,?,?,?)",
          id,
          p.tenantId(),
          input.url(),
          input.description(),
          input.active(),
          crypto.encrypt(secret),
          input.maxAttempts(),
          input.retryBaseSeconds(),
          input.retryMaxSeconds());
    } else {
      get(p.tenantId(), id);
      db.update(
          "UPDATE webhook_endpoints SET url=?,description=?,active=?,max_attempts=?,retry_base_seconds=?,retry_max_seconds=?,updated_at=now() WHERE tenant_id=? AND id=?",
          input.url(),
          input.description(),
          input.active(),
          input.maxAttempts(),
          input.retryBaseSeconds(),
          input.retryMaxSeconds(),
          p.tenantId(),
          id);
      db.update("DELETE FROM webhook_subscriptions WHERE endpoint_id=?", id);
    }
    for (String event : new HashSet<>(input.subscribedEvents()))
      db.update("INSERT INTO webhook_subscriptions(endpoint_id,event_type) VALUES(?,?)", id, event);
    audit.record(p, create ? "endpoint.created" : "endpoint.updated", id);
    var result = get(p.tenantId(), id);
    if (create) result.put("signingSecret", secret);
    return result;
  }

  @Transactional
  public Map<String, Object> rotate(TenantPrincipal p, UUID id) {
    p.requireDeveloper();
    get(p.tenantId(), id);
    String secret = crypto.randomToken();
    db.update(
        "UPDATE webhook_endpoints SET previous_secret=signing_secret,previous_secret_expires_at=now()+interval '24 hours',signing_secret=?,updated_at=now() WHERE tenant_id=? AND id=?",
        crypto.encrypt(secret),
        p.tenantId(),
        id);
    audit.record(p, "endpoint.secret_rotated", id);
    return Map.of("id", id, "signingSecret", secret);
  }

  @Transactional
  public void delete(TenantPrincipal p, UUID id) {
    p.requireDeveloper();
    get(p.tenantId(), id);
    db.update(
        "UPDATE webhook_endpoints SET active=false,deleted_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",
        p.tenantId(),
        id);
    audit.record(p, "endpoint.deleted", id);
  }

  public record Input(
      @NotBlank @Size(max = 2048) String url,
      @NotNull @Size(max = 500) String description,
      boolean active,
      @NotEmpty @Size(max = 100)
          List<@Pattern(regexp = "\\*|[a-zA-Z0-9][a-zA-Z0-9._-]{0,119}") String> subscribedEvents,
      @Min(1) @Max(10) int maxAttempts,
      @Min(1) @Max(3600) int retryBaseSeconds,
      @Min(1) @Max(86400) int retryMaxSeconds) {
    public Input {
      if (description == null) description = "";
      if (maxAttempts == 0) maxAttempts = 5;
      if (retryBaseSeconds == 0) retryBaseSeconds = 10;
      if (retryMaxSeconds == 0) retryMaxSeconds = 600;
    }
  }
}
