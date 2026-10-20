package io.dispatchgrid.api.security;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessTest {
  @Test
  void jwtIsSignedAndTenantScoped() {
    var service = new JwtService("test-secret-with-at-least-thirty-two-characters");
    UUID user = UUID.randomUUID(), tenant = UUID.randomUUID();
    String token = service.issue(user, tenant);
    assertThat(service.verify(token)).isEqualTo(new JwtService.Identity(user, tenant));
    assertThatThrownBy(
            () ->
                new JwtService("another-test-secret-with-at-least-thirty-two-characters")
                    .verify(token))
        .isInstanceOf(io.dispatchgrid.common.ApiException.class);
  }

  @Test
  void viewerAndPublishingKeyCannotManageResources() {
    for (var p :
        new TenantPrincipal[] {
          new TenantPrincipal(
              UUID.randomUUID(), UUID.randomUUID(), TenantPrincipal.Role.VIEWER, false, 600),
          new TenantPrincipal(UUID.randomUUID(), null, TenantPrincipal.Role.DEVELOPER, true, 600)
        }) {
      assertThatThrownBy(p::requireAdmin).isInstanceOf(io.dispatchgrid.common.ApiException.class);
      assertThatThrownBy(p::requireDeveloper)
          .isInstanceOf(io.dispatchgrid.common.ApiException.class);
    }
  }
}
