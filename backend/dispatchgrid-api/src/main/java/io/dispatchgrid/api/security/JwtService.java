package io.dispatchgrid.api.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.dispatchgrid.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
  public static final int ACCESS_SECONDS = 900;
  private final byte[] secret;

  public JwtService(@Value("${dispatchgrid.jwt-secret}") String secret) {
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    if (this.secret.length < 32)
      throw new IllegalArgumentException("JWT_SECRET requires at least 32 bytes");
  }

  public String issue(UUID userId, UUID tenantId) {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .subject(userId.toString())
            .issuer("dispatchgrid")
            .audience("dispatchgrid-dashboard")
            .claim("tenantId", tenantId.toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(ACCESS_SECONDS)))
            .jwtID(UUID.randomUUID().toString())
            .build();
    try {
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(secret));
      return jwt.serialize();
    } catch (JOSEException ex) {
      throw new IllegalStateException("Could not sign access token", ex);
    }
  }

  public Identity verify(String token) {
    try {
      SignedJWT jwt = SignedJWT.parse(token);
      if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
          || !jwt.verify(new MACVerifier(secret))) throw new IllegalArgumentException();
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      if (!"dispatchgrid".equals(claims.getIssuer())
          || !claims.getAudience().contains("dispatchgrid-dashboard")
          || claims.getExpirationTime() == null
          || !claims.getExpirationTime().toInstant().isAfter(Instant.now()))
        throw new IllegalArgumentException();
      return new Identity(
          UUID.fromString(claims.getSubject()), UUID.fromString(claims.getStringClaim("tenantId")));
    } catch (Exception ex) {
      throw new ApiException(401, "Invalid or expired access token");
    }
  }

  public record Identity(UUID userId, UUID tenantId) {}
}
