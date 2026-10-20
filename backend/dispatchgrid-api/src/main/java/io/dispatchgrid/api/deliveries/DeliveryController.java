package io.dispatchgrid.api.deliveries;

import io.dispatchgrid.api.security.TenantPrincipal;
import io.dispatchgrid.api.web.*;
import io.dispatchgrid.common.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class DeliveryController {
  private final JdbcTemplate db;
  private final SqlViews views;
  private final AuditLog audit;

  public DeliveryController(JdbcTemplate db, SqlViews views, AuditLog audit) {
    this.db = db;
    this.views = views;
    this.audit = audit;
  }

  @GetMapping("/deliveries")
  public Object list(
      @AuthenticationPrincipal TenantPrincipal p,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID eventId,
      @RequestParam(required = false) UUID endpointId) {
    int offset = PageResult.offset(page, size);
    String filter = "d.tenant_id=?";
    var args = new ArrayList<Object>();
    args.add(p.tenantId());
    if (status != null && !status.isBlank()) {
      DeliveryStatus.valueOf(status);
      filter += " AND d.status=?";
      args.add(status);
    }
    if (eventId != null) {
      filter += " AND d.event_id=?";
      args.add(eventId);
    }
    if (endpointId != null) {
      filter += " AND d.endpoint_id=?";
      args.add(endpointId);
    }
    long total =
        db.queryForObject(
            "SELECT count(*) FROM webhook_deliveries d WHERE " + filter,
            Long.class,
            args.toArray());
    args.add(size);
    args.add(offset);
    return new PageResult<>(
        db.query(
            "SELECT d.id,d.event_id,d.endpoint_id,d.status,d.attempt_count,d.replay_count,d.last_status_code,d.last_error,d.created_at,d.next_attempt_at,e.event_type,w.url FROM webhook_deliveries d JOIN events e ON e.id=d.event_id JOIN webhook_endpoints w ON w.id=d.endpoint_id WHERE "
                + filter
                + " ORDER BY d.created_at DESC LIMIT ? OFFSET ?",
            views,
            args.toArray()),
        page,
        size,
        total);
  }

  @GetMapping("/deliveries/{id}")
  public Map<String, Object> get(
      @AuthenticationPrincipal TenantPrincipal p, @PathVariable UUID id) {
    var item = find(p.tenantId(), id);
    item.put("attempts", attempts(p, id));
    return item;
  }

  private Map<String, Object> find(UUID tenant, UUID id) {
    return db
        .query(
            "SELECT d.id,d.event_id,d.endpoint_id,d.status,d.attempt_count,d.replay_count,d.last_status_code,d.last_error,d.created_at,d.next_attempt_at,d.delivered_at,e.event_type,e.payload,w.url FROM webhook_deliveries d JOIN events e ON e.id=d.event_id JOIN webhook_endpoints w ON w.id=d.endpoint_id WHERE d.tenant_id=? AND d.id=?",
            views,
            tenant,
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(404, "Delivery not found"));
  }

  @GetMapping("/deliveries/{id}/attempts")
  public Object attempts(@AuthenticationPrincipal TenantPrincipal p, @PathVariable UUID id) {
    find(p.tenantId(), id);
    return db.query(
        "SELECT attempt_number,replay_number,status_code,latency_ms,outcome,error,signature,started_at,completed_at FROM delivery_attempts WHERE tenant_id=? AND delivery_id=? ORDER BY replay_number,attempt_number",
        views,
        p.tenantId(),
        id);
  }

  @PostMapping("/deliveries/{id}/replay")
  @Transactional
  public Object replay(@AuthenticationPrincipal TenantPrincipal p, @PathVariable UUID id) {
    p.requireDeveloper();
    find(p.tenantId(), id);
    if (db.update(
            "UPDATE webhook_deliveries SET status='PENDING',attempt_count=0,replay_count=replay_count+1,next_attempt_at=now(),lease_token=NULL,lease_until=NULL,last_error=NULL,last_status_code=NULL,delivered_at=NULL,updated_at=now() WHERE tenant_id=? AND id=? AND status IN ('FAILED','EXHAUSTED')",
            p.tenantId(),
            id)
        != 1) throw new ApiException(409, "Only failed or exhausted deliveries can be replayed");
    audit.record(p, "delivery.replayed", id);
    return find(p.tenantId(), id);
  }

  @GetMapping("/dead-letters")
  public Object dead(
      @AuthenticationPrincipal TenantPrincipal p,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    int offset = PageResult.offset(page, size);
    return new PageResult<>(
        db.query(
            "SELECT id,delivery_id,replay_number,reason,created_at FROM dead_letters WHERE tenant_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?",
            views,
            p.tenantId(),
            size,
            offset),
        page,
        size,
        db.queryForObject(
            "SELECT count(*) FROM dead_letters WHERE tenant_id=?", Long.class, p.tenantId()));
  }
}
