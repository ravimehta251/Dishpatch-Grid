package io.dispatchgrid.api.endpoints;

import io.dispatchgrid.api.security.TenantPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/endpoints")
public class EndpointController {
  private final EndpointService service;

  public EndpointController(EndpointService service) {
    this.service = service;
  }

  @GetMapping
  public Object list(
      @AuthenticationPrincipal TenantPrincipal p,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.list(p.tenantId(), page, size);
  }

  @GetMapping("/{id}")
  public Object get(@AuthenticationPrincipal TenantPrincipal p, @PathVariable UUID id) {
    return service.get(p.tenantId(), id);
  }

  @PostMapping
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Object create(
      @AuthenticationPrincipal TenantPrincipal p, @Valid @RequestBody EndpointService.Input input) {
    return service.save(p, null, input);
  }

  @PutMapping("/{id}")
  public Object update(
      @AuthenticationPrincipal TenantPrincipal p,
      @PathVariable UUID id,
      @Valid @RequestBody EndpointService.Input input) {
    return service.save(p, id, input);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal TenantPrincipal p, @PathVariable UUID id) {
    service.delete(p, id);
  }

  @PostMapping("/{id}/rotate-secret")
  public Object rotate(@AuthenticationPrincipal TenantPrincipal p, @PathVariable UUID id) {
    return service.rotate(p, id);
  }
}
