package io.dispatchgrid.api.identity;

import io.dispatchgrid.api.security.JwtService;
import io.dispatchgrid.common.ApiException;
import io.dispatchgrid.common.CryptoService;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final UserRepository users;
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final JwtService jwt;
  private final CryptoService crypto;
  private final String dummyHash;

  public AuthService(
      UserRepository users,
      JdbcTemplate jdbc,
      PasswordEncoder passwords,
      JwtService jwt,
      CryptoService crypto) {
    this.users = users;
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.jwt = jwt;
    this.crypto = crypto;
    this.dummyHash = passwords.encode("invalid-account-placeholder");
  }

  @Transactional
  public Session register(String tenantName, String email, String password) {
    email = normalizeEmail(email);
    if (users.findByEmail(email).isPresent())
      throw new ApiException(409, "An account with this email already exists");
    var user =
        users.saveAndFlush(new UserAccount(UUID.randomUUID(), email, passwords.encode(password)));
    UUID tenantId = UUID.randomUUID();
    jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", tenantId, tenantName.trim());
    jdbc.update(
        "INSERT INTO tenant_memberships(tenant_id,user_id,role) VALUES (?,?,'OWNER')",
        tenantId,
        user.getId());
    return newSession(user.getId(), tenantId);
  }

  @Transactional
  public Session login(String email, String password, UUID tenantId) {
    var user = users.findByEmail(normalizeEmail(email)).orElse(null);
    boolean matches =
        passwords.matches(password, user == null ? dummyHash : user.getPasswordHash());
    if (user == null || !matches) throw new ApiException(401, "Invalid email or password");
    if (tenantId == null) {
      tenantId =
          jdbc
              .query(
                  "SELECT tenant_id FROM tenant_memberships WHERE user_id=? ORDER BY tenant_id LIMIT 1",
                  (rs, row) -> rs.getObject(1, UUID.class),
                  user.getId())
              .stream()
              .findFirst()
              .orElseThrow(() -> new ApiException(401, "No tenant membership"));
    }
    return newSession(user.getId(), tenantId);
  }

  @Transactional
  public Session refresh(String token) {
    if (token == null || token.length() > 256)
      throw new ApiException(401, "Refresh session required");
    var rows =
        jdbc.query(
            "SELECT id,user_id,tenant_id FROM refresh_tokens WHERE token_hash=? AND revoked_at IS NULL AND expires_at>now() FOR UPDATE",
            (rs, row) ->
                new UUID[] {
                  rs.getObject(1, UUID.class),
                  rs.getObject(2, UUID.class),
                  rs.getObject(3, UUID.class)
                },
            crypto.sha256(token));
    if (rows.isEmpty()) throw new ApiException(401, "Invalid or expired refresh session");
    var row = rows.getFirst();
    jdbc.update("UPDATE refresh_tokens SET revoked_at=now() WHERE id=?", row[0]);
    return newSession(row[1], row[2]);
  }

  @Transactional
  public void logout(String token) {
    if (token != null && token.length() <= 256)
      jdbc.update(
          "UPDATE refresh_tokens SET revoked_at=now() WHERE token_hash=? AND revoked_at IS NULL",
          crypto.sha256(token));
  }

  public Map<String, Object> identity(UUID userId, UUID tenantId) {
    return jdbc
        .query(
            "SELECT u.email,t.name,t.rate_limit_per_minute,m.role FROM users u JOIN tenant_memberships m ON m.user_id=u.id JOIN tenants t ON t.id=m.tenant_id WHERE u.id=? AND t.id=?",
            (rs, row) ->
                Map.<String, Object>of(
                    "user",
                    Map.of("id", userId, "email", rs.getString(1)),
                    "tenant",
                    Map.of(
                        "id",
                        tenantId,
                        "name",
                        rs.getString(2),
                        "rateLimitPerMinute",
                        rs.getInt(3),
                        "role",
                        rs.getString(4))),
            userId,
            tenantId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(401, "No tenant membership"));
  }

  private Session newSession(UUID userId, UUID tenantId) {
    var identity = identity(userId, tenantId);
    String refresh = crypto.randomToken();
    jdbc.update(
        "INSERT INTO refresh_tokens(id,user_id,tenant_id,token_hash,expires_at) VALUES (?,?,?,?,?)",
        UUID.randomUUID(),
        userId,
        tenantId,
        crypto.sha256(refresh),
        java.sql.Timestamp.from(Instant.now().plusSeconds(604800)));
    return new Session(
        Map.of(
            "accessToken",
            jwt.issue(userId, tenantId),
            "expiresIn",
            JwtService.ACCESS_SECONDS,
            "user",
            identity.get("user"),
            "tenant",
            identity.get("tenant")),
        refresh);
  }

  public static String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  public record Session(Map<String, Object> body, String refreshToken) {}
}
