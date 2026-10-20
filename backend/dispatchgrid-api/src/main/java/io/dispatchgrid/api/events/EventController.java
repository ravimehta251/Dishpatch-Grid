package io.dispatchgrid.api.events;

import com.fasterxml.jackson.databind.JsonNode;
import io.dispatchgrid.api.security.TenantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {
  private final EventService service;

  public EventController(EventService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public Object publish(
      @AuthenticationPrincipal TenantPrincipal p,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Input input,
      HttpServletRequest r) {
    return service.ingest(
        p, key, input.eventType(), input.payload(), r.getAttribute("correlationId").toString());
  }

  @GetMapping
  public Object list(
      @AuthenticationPrincipal TenantPrincipal p,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String eventType) {
    return service.list(p.tenantId(), page, size, eventType);
  }

  @GetMapping("/{id}")
  public Object get(@AuthenticationPrincipal TenantPrincipal p, @PathVariable UUID id) {
    return service.get(p.tenantId(), id);
  }

  public record Input(
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{0,119}") String eventType,
      @NotNull JsonNode payload) {}
}
