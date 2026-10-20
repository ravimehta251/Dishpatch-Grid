package io.dispatchgrid.api.identity;

import io.dispatchgrid.api.security.TenantPrincipal;
import io.dispatchgrid.common.ApiException;
import io.dispatchgrid.common.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private static final String COOKIE = "dispatchgrid_refresh";
  private final AuthService auth;
  private final RateLimiter limiter;
  private final boolean secure;

  public AuthController(
      AuthService auth,
      RateLimiter limiter,
      @Value("${dispatchgrid.cookie-secure:true}") boolean secure) {
    this.auth = auth;
    this.limiter = limiter;
    this.secure = secure;
  }

  @PostMapping("/register")
  public ResponseEntity<?> register(
      @Valid @RequestBody Registration body, HttpServletRequest request) {
    throttle(request);
    return session(auth.register(body.tenantName(), body.email(), body.password()), 201);
  }

  @PostMapping("/login")
  public ResponseEntity<?> login(@Valid @RequestBody Login body, HttpServletRequest request) {
    throttle(request);
    return session(auth.login(body.email(), body.password(), body.tenantId()), 200);
  }

  @PostMapping("/refresh")
  public ResponseEntity<?> refresh(
      @CookieValue(name = COOKIE, required = false) String token, HttpServletRequest request) {
    sameOrigin(request);
    throttle(request);
    return session(auth.refresh(token), 200);
  }

  @PostMapping("/logout")
  public ResponseEntity<?> logout(
      @CookieValue(name = COOKIE, required = false) String token, HttpServletRequest request) {
    sameOrigin(request);
    auth.logout(token);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
        .build();
  }

  @GetMapping("/me")
  public Map<String, Object> me(@AuthenticationPrincipal TenantPrincipal principal) {
    return auth.identity(principal.userId(), principal.tenantId());
  }

  private ResponseEntity<?> session(AuthService.Session session, int status) {
    return ResponseEntity.status(status)
        .header(
            HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), Duration.ofDays(7)).toString())
        .body(session.body());
  }

  private ResponseCookie cookie(String value, Duration age) {
    return ResponseCookie.from(COOKIE, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Strict")
        .path("/api/v1/auth")
        .maxAge(age)
        .build();
  }

  private void throttle(HttpServletRequest request) {
    limiter.check(
        UUID.nameUUIDFromBytes(
            ("auth:" + request.getRemoteAddr()).getBytes(StandardCharsets.UTF_8)),
        30);
  }

  private void sameOrigin(HttpServletRequest request) {
    String origin = request.getHeader("Origin");
    if (origin == null) return; // Non-browser clients are not subject to CSRF.
    try {
      URI uri = URI.create(origin);
      String expected = request.getScheme() + "://" + request.getHeader("Host");
      if (!origin.equalsIgnoreCase(expected) || uri.getHost() == null)
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException ex) {
      throw new ApiException(403, "Cross-origin session requests are forbidden");
    }
  }

  public record Registration(
      @NotBlank @Size(max = 120) String tenantName,
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 12, max = 72) String password) {}

  public record Login(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(max = 72) String password,
      UUID tenantId) {}
}
