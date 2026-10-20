package io.dispatchgrid.api.security;

import io.dispatchgrid.common.ApiException;
import java.util.UUID;

public record TenantPrincipal(UUID tenantId, UUID userId, Role role, boolean apiKey, int quota) {
  public enum Role {
    OWNER,
    ADMIN,
    DEVELOPER,
    VIEWER
  }

  public void requireDeveloper() {
    if (apiKey || role == Role.VIEWER) throw new ApiException(403, "Developer access is required");
  }

  public void requireAdmin() {
    if (apiKey || (role != Role.OWNER && role != Role.ADMIN))
      throw new ApiException(403, "Administrator access is required");
  }
}
