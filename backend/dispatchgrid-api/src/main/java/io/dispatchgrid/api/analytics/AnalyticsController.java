package io.dispatchgrid.api.analytics;

import io.dispatchgrid.api.security.TenantPrincipal;
import io.dispatchgrid.api.web.SqlViews;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {
  private final JdbcTemplate db;
  private final SqlViews views;

  public AnalyticsController(JdbcTemplate db, SqlViews views) {
    this.db = db;
    this.views = views;
  }

  @GetMapping("/overview")
  public Object overview(@AuthenticationPrincipal TenantPrincipal p) {
    UUID t = p.tenantId();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("eventsReceived", count("events", t, ""));
    result.put("successfulDeliveries", count("webhook_deliveries", t, " AND status='DELIVERED'"));
    result.put(
        "failedDeliveries",
        count("webhook_deliveries", t, " AND status IN ('FAILED','EXHAUSTED')"));
    result.put(
        "pendingDeliveries",
        count(
            "webhook_deliveries", t, " AND status IN ('PENDING','PROCESSING','RETRY_SCHEDULED')"));
    long total = count("webhook_deliveries", t, ""),
        success = (Long) result.get("successfulDeliveries");
    result.put("successRate", total == 0 ? 0 : 100.0 * success / total);
    result.put(
        "averageDeliveryTimeMs",
        db.queryForObject(
            "SELECT coalesce(avg(latency_ms),0) FROM delivery_attempts WHERE tenant_id=?",
            Double.class,
            t));
    result.put("retryCount", count("delivery_attempts", t, " AND attempt_number>1"));
    result.put("deadLetterCount", count("dead_letters", t, ""));
    result.put(
        "activeEndpoints", count("webhook_endpoints", t, " AND active AND deleted_at IS NULL"));
    return result;
  }

  private long count(String table, UUID tenant, String filter) {
    return db.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE tenant_id=?" + filter, Long.class, tenant);
  }

  @GetMapping({"/timeseries", "/deliveries"})
  public Object timeseries(@AuthenticationPrincipal TenantPrincipal p) {
    return db.query(
        """
 SELECT day::date AS date,
 (SELECT count(*) FROM events e WHERE e.tenant_id=? AND e.created_at>=day AND e.created_at<day+interval '1 day') AS events,
 (SELECT count(*) FROM webhook_deliveries d WHERE d.tenant_id=? AND d.created_at>=day AND d.created_at<day+interval '1 day' AND status='DELIVERED') AS delivered,
 (SELECT count(*) FROM webhook_deliveries d WHERE d.tenant_id=? AND d.created_at>=day AND d.created_at<day+interval '1 day' AND status IN ('FAILED','EXHAUSTED')) AS failed
 FROM generate_series(date_trunc('day',now())-interval '13 days',date_trunc('day',now()),interval '1 day') AS day
 """,
        views,
        p.tenantId(),
        p.tenantId(),
        p.tenantId());
  }

  @GetMapping("/failures")
  public Object failures(@AuthenticationPrincipal TenantPrincipal p) {
    return db.query(
        "SELECT coalesce(last_error,'Unknown') AS reason,count(*) AS count FROM webhook_deliveries WHERE tenant_id=? AND status IN ('FAILED','EXHAUSTED') GROUP BY last_error ORDER BY count(*) DESC LIMIT 20",
        views,
        p.tenantId());
  }
}
