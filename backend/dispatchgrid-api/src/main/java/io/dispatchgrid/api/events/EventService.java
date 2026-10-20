package io.dispatchgrid.api.events;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.dispatchgrid.api.security.TenantPrincipal;
import io.dispatchgrid.api.web.*;
import io.dispatchgrid.common.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
  private final JdbcTemplate db;
  private final ObjectMapper mapper;
  private final SqlViews views;
  private final CryptoService crypto;
  private final RateLimiter rate;
  private final MeterRegistry metrics;

  public EventService(
      JdbcTemplate db,
      ObjectMapper mapper,
      SqlViews views,
      CryptoService crypto,
      RateLimiter rate,
      MeterRegistry metrics) {
    this.db = db;
    this.mapper = mapper;
    this.views = views;
    this.crypto = crypto;
    this.rate = rate;
    this.metrics = metrics;
  }

  @Transactional
  public Map<String, Object> ingest(
      TenantPrincipal p, String key, String type, JsonNode payload, String correlation) {
    if (!p.apiKey()) p.requireDeveloper();
    if (key == null || key.isBlank() || key.length() > 200)
      throw new ApiException(400, "Idempotency-Key is required (1–200 characters)");
    if (payload == null || !payload.isObject())
      throw new ApiException(400, "payload must be a JSON object");
    rate.check(p.tenantId(), p.quota());
    String hash = crypto.sha256(type + ":" + canonical(payload));
    db.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> {}, p.tenantId() + ":" + key);
    db.update(
        "DELETE FROM idempotency_records WHERE tenant_id=? AND idempotency_key=? AND expires_at<=now()",
        p.tenantId(),
        key);
    var existing =
        db.queryForList(
            "SELECT event_id,request_hash FROM idempotency_records WHERE tenant_id=? AND idempotency_key=?",
            p.tenantId(),
            key);
    if (!existing.isEmpty()) {
      var old = existing.getFirst();
      if (!hash.equals(old.get("request_hash")))
        throw new ApiException(409, "Idempotency key already used with different event content");
      var result = get(p.tenantId(), (UUID) old.get("event_id"));
      result.remove("payload");
      result.put("duplicate", true);
      return result;
    }
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO events(id,tenant_id,event_type,payload,correlation_id) VALUES(?,?,?,?::jsonb,?)",
        id,
        p.tenantId(),
        type,
        payload.toString(),
        correlation);
    db.update(
        "INSERT INTO idempotency_records(tenant_id,idempotency_key,request_hash,event_id,expires_at) VALUES(?,?,?,?,now()+interval '24 hours')",
        p.tenantId(),
        key,
        hash,
        id);
    ObjectNode envelope =
        mapper
            .createObjectNode()
            .put("eventId", id.toString())
            .put("tenantId", p.tenantId().toString());
    db.update(
        "INSERT INTO outbox_messages(id,topic,message_key,payload) VALUES(?,?,?,?)",
        UUID.randomUUID(),
        "dispatchgrid.events",
        p.tenantId().toString(),
        envelope.toString());
    org.springframework.transaction.support.TransactionSynchronizationManager
        .registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              @Override
              public void afterCommit() {
                metrics.counter("dispatchgrid.events.ingested").increment();
              }
            });
    var result = get(p.tenantId(), id);
    result.remove("payload");
    result.put("duplicate", false);
    return result;
  }

  private JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      ObjectNode sorted = mapper.createObjectNode();
      var names = new TreeSet<String>();
      node.fieldNames().forEachRemaining(names::add);
      names.forEach(n -> sorted.set(n, canonical(node.get(n))));
      return sorted;
    }
    if (node.isArray()) {
      ArrayNode a = mapper.createArrayNode();
      node.forEach(n -> a.add(canonical(n)));
      return a;
    }
    return node;
  }

  public Map<String, Object> get(UUID tenant, UUID id) {
    return db
        .query(
            "SELECT id,event_type,payload,correlation_id,created_at FROM events WHERE tenant_id=? AND id=?",
            views,
            tenant,
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(404, "Event not found"));
  }

  public Object list(UUID tenant, int page, int size, String type) {
    int offset = PageResult.offset(page, size);
    String filter = "tenant_id=?";
    var args = new ArrayList<Object>();
    args.add(tenant);
    if (type != null && !type.isBlank()) {
      filter += " AND event_type=?";
      args.add(type);
    }
    Long total =
        db.queryForObject(
            "SELECT count(*) FROM events WHERE " + filter, Long.class, args.toArray());
    args.add(size);
    args.add(offset);
    return new PageResult<>(
        db.query(
            "SELECT id,event_type,correlation_id,created_at FROM events WHERE "
                + filter
                + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
            views,
            args.toArray()),
        page,
        size,
        total);
  }
}
