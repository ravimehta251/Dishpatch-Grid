package io.dispatchgrid.api.keys;

import io.dispatchgrid.api.security.TenantPrincipal;
import io.dispatchgrid.api.web.*;
import io.dispatchgrid.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/api-keys")
public class ApiKeyController {
  private final JdbcTemplate db;
  private final CryptoService crypto;
  private final SqlViews views;
  private final AuditLog audit;

  public ApiKeyController(JdbcTemplate db, CryptoService crypto, SqlViews views, AuditLog audit) {
    this.db = db;
    this.crypto = crypto;
    this.views = views;
    this.audit = audit;
  }

  @GetMapping
  public Object list(
      @AuthenticationPrincipal TenantPrincipal p,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    p.requireAdmin();
    int offset = PageResult.offset(page, size);
    return new PageResult<>(
        db.query(
            "SELECT id,name,key_prefix,created_at,revoked_at FROM api_keys WHERE tenant_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?",
            views,
            p.tenantId(),
            size,
            offset),
        page,
        size,
        db.queryForObject(
            "SELECT count(*) FROM api_keys WHERE tenant_id=?", Long.class, p.tenantId()));
  }

  @PostMapping
  @Transactional
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Object create(@AuthenticationPrincipal TenantPrincipal p, @Valid @RequestBody Input body) {
    p.requireAdmin();
    String key = "dg_" + crypto.randomToken();
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO api_keys(id,tenant_id,name,key_prefix,key_hash) VALUES(?,?,?,?,?)",
        id,
        p.tenantId(),
        body.name(),
        key.substring(0, 12),
        crypto.sha256(key));
    audit.record(p, "api_key.created", id);
    var row =
        db.query("SELECT id,name,key_prefix,created_at FROM api_keys WHERE id=?", views, id)
            .getFirst();
    row.put("key", key);
    return row;
  }

  @DeleteMapping("/{id}")
  @Transactional
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void revoke(@AuthenticationPrincipal TenantPrincipal p, @PathVariable UUID id) {
    p.requireAdmin();
    if (db.update(
            "UPDATE api_keys SET revoked_at=coalesce(revoked_at,now()) WHERE tenant_id=? AND id=?",
            p.tenantId(),
            id)
        == 0) throw new ApiException(404, "API key not found");
    audit.record(p, "api_key.revoked", id);
  }

  public record Input(@NotBlank @Size(max = 100) String name) {}
}
