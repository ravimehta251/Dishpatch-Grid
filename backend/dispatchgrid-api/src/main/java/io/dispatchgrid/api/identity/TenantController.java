package io.dispatchgrid.api.identity;

import io.dispatchgrid.api.security.TenantPrincipal;
import io.dispatchgrid.api.web.*;
import io.dispatchgrid.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenant")
public class TenantController {
  private final JdbcTemplate db;
  private final SqlViews views;
  private final PasswordEncoder passwords;
  private final AuditLog audit;

  public TenantController(
      JdbcTemplate db, SqlViews views, PasswordEncoder passwords, AuditLog audit) {
    this.db = db;
    this.views = views;
    this.passwords = passwords;
    this.audit = audit;
  }

  @GetMapping
  public Object get(@AuthenticationPrincipal TenantPrincipal p) {
    return db.query(
            "SELECT id,name,rate_limit_per_minute,created_at FROM tenants WHERE id=?",
            views,
            p.tenantId())
        .getFirst();
  }

  @PutMapping
  @Transactional
  public Object update(
      @AuthenticationPrincipal TenantPrincipal p, @Valid @RequestBody Settings body) {
    p.requireAdmin();
    db.update(
        "UPDATE tenants SET name=?,rate_limit_per_minute=? WHERE id=?",
        body.name(),
        body.rateLimitPerMinute(),
        p.tenantId());
    audit.record(p, "tenant.updated", p.tenantId());
    return get(p);
  }

  @GetMapping("/members")
  public Object members(@AuthenticationPrincipal TenantPrincipal p) {
    p.requireAdmin();
    return db.query(
        "SELECT u.id,u.email,m.role FROM tenant_memberships m JOIN users u ON u.id=m.user_id WHERE m.tenant_id=? ORDER BY u.email",
        views,
        p.tenantId());
  }

  @PutMapping("/members")
  @Transactional
  public Object member(
      @AuthenticationPrincipal TenantPrincipal p, @Valid @RequestBody Member body) {
    p.requireAdmin();
    TenantPrincipal.Role role = TenantPrincipal.Role.valueOf(body.role());
    if (role == TenantPrincipal.Role.OWNER && p.role() != TenantPrincipal.Role.OWNER)
      throw new ApiException(403, "Only owners can appoint owners");
    db.queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE", p.tenantId());
    var found =
        db.queryForList(
            "SELECT id FROM users WHERE email=?", AuthService.normalizeEmail(body.email()));
    UUID id;
    if (found.isEmpty()) {
      if (body.password() == null || body.password().length() < 12 || body.password().length() > 72)
        throw new ApiException(400, "New accounts require a password of 12–72 characters");
      id = UUID.randomUUID();
      db.update(
          "INSERT INTO users(id,email,password_hash) VALUES(?,?,?)",
          id,
          AuthService.normalizeEmail(body.email()),
          passwords.encode(body.password()));
    } else {
      id = (UUID) found.getFirst().get("id");
      guardOwner(p, id);
    }
    db.update(
        "INSERT INTO tenant_memberships(tenant_id,user_id,role) VALUES(?,?,?) ON CONFLICT(tenant_id,user_id) DO UPDATE SET role=excluded.role",
        p.tenantId(),
        id,
        role.name());
    audit.record(p, "member.updated", id);
    return members(p);
  }

  @DeleteMapping("/members/{id}")
  @Transactional
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void remove(@AuthenticationPrincipal TenantPrincipal p, @PathVariable UUID id) {
    p.requireAdmin();
    db.queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE", p.tenantId());
    guardOwner(p, id);
    if (db.update(
            "DELETE FROM tenant_memberships WHERE tenant_id=? AND user_id=?", p.tenantId(), id)
        == 0) throw new ApiException(404, "Member not found");
    audit.record(p, "member.removed", id);
  }

  private void guardOwner(TenantPrincipal p, UUID id) {
    var roles =
        db.queryForList(
            "SELECT role FROM tenant_memberships WHERE tenant_id=? AND user_id=?",
            String.class,
            p.tenantId(),
            id);
    if (roles.contains("OWNER")) {
      if (p.role() != TenantPrincipal.Role.OWNER)
        throw new ApiException(403, "Only owners can change owners");
      if (db.queryForObject(
              "SELECT count(*) FROM tenant_memberships WHERE tenant_id=? AND role='OWNER'",
              Long.class,
              p.tenantId())
          <= 1) throw new ApiException(409, "Cannot remove or change the last owner");
    }
  }

  public record Settings(
      @NotBlank @Size(max = 120) String name, @Min(1) @Max(60000) int rateLimitPerMinute) {}

  public record Member(@Email @NotBlank String email, String password, @NotBlank String role) {}
}
