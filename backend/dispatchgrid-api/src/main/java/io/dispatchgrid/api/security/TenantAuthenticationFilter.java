package io.dispatchgrid.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dispatchgrid.api.web.Problems;
import io.dispatchgrid.common.ApiException;
import io.dispatchgrid.common.CryptoService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TenantAuthenticationFilter extends OncePerRequestFilter {
  private final JdbcTemplate jdbc;
  private final JwtService jwt;
  private final CryptoService crypto;
  private final ObjectMapper mapper;
  private final String monitoringToken;

  public TenantAuthenticationFilter(
      JdbcTemplate jdbc,
      JwtService jwt,
      CryptoService crypto,
      ObjectMapper mapper,
      @Value("${dispatchgrid.monitoring-token}") String monitoringToken) {
    this.jdbc = jdbc;
    this.jwt = jwt;
    this.crypto = crypto;
    this.mapper = mapper;
    this.monitoringToken = monitoringToken;
    if (monitoringToken.length() < 32)
      throw new IllegalArgumentException("MONITORING_TOKEN requires at least 32 characters");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    String header = request.getHeader("Authorization");
    if (path.equals("/actuator/prometheus")) {
      if (header == null
          || !MessageDigest.isEqual(
              header.getBytes(StandardCharsets.UTF_8),
              ("Bearer " + monitoringToken).getBytes(StandardCharsets.UTF_8))) {
        Problems.write(mapper, request, response, 401, "Monitoring authentication required");
        return;
      }
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  "monitoring", null, List.of(new SimpleGrantedAuthority("ROLE_MONITORING"))));
    } else if (header != null
        && header.startsWith("Bearer ")
        && !java.util.Set.of(
                "/api/v1/auth/login",
                "/api/v1/auth/register",
                "/api/v1/auth/refresh",
                "/api/v1/auth/logout")
            .contains(path)) {
      String token = header.substring(7);
      TenantPrincipal principal;
      try {
        if (token.startsWith("dg_")) {
          if (!"POST".equals(request.getMethod()) || !path.equals("/api/v1/events")) {
            Problems.write(mapper, request, response, 403, "API keys may only publish events");
            return;
          }
          principal =
              jdbc
                  .query(
                      "SELECT k.tenant_id,t.rate_limit_per_minute FROM api_keys k JOIN tenants t ON t.id=k.tenant_id WHERE k.key_hash=? AND k.revoked_at IS NULL",
                      (rs, row) ->
                          new TenantPrincipal(
                              rs.getObject(1, UUID.class),
                              null,
                              TenantPrincipal.Role.DEVELOPER,
                              true,
                              rs.getInt(2)),
                      crypto.sha256(token))
                  .stream()
                  .findFirst()
                  .orElseThrow(() -> new ApiException(401, "Invalid API key"));
        } else {
          var identity = jwt.verify(token);
          principal =
              jdbc
                  .query(
                      "SELECT m.role,t.rate_limit_per_minute FROM tenant_memberships m JOIN tenants t ON t.id=m.tenant_id WHERE m.tenant_id=? AND m.user_id=?",
                      (rs, row) ->
                          new TenantPrincipal(
                              identity.tenantId(),
                              identity.userId(),
                              TenantPrincipal.Role.valueOf(rs.getString(1)),
                              false,
                              rs.getInt(2)),
                      identity.tenantId(),
                      identity.userId())
                  .stream()
                  .findFirst()
                  .orElseThrow(() -> new ApiException(401, "Tenant membership no longer exists"));
        }
      } catch (ApiException ex) {
        Problems.write(mapper, request, response, 401, "Invalid or expired credentials");
        return;
      }
      MDC.put("tenantId", principal.tenantId().toString());
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  principal,
                  null,
                  List.of(new SimpleGrantedAuthority("ROLE_" + principal.role()))));
    }
    chain.doFilter(request, response);
  }
}
