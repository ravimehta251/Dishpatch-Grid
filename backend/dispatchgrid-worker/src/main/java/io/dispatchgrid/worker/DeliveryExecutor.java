package io.dispatchgrid.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dispatchgrid.common.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DeliveryExecutor {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final CryptoService crypto;
  private final DestinationPolicy policy;
  private final CloseableHttpClient http;
  private final ObjectMapper mapper;
  private final DeadLetters dlq;
  private final MeterRegistry metrics;

  public DeliveryExecutor(
      JdbcTemplate db,
      TransactionTemplate tx,
      CryptoService crypto,
      DestinationPolicy policy,
      CloseableHttpClient http,
      ObjectMapper mapper,
      DeadLetters dlq,
      MeterRegistry metrics) {
    this.db = db;
    this.tx = tx;
    this.crypto = crypto;
    this.policy = policy;
    this.http = http;
    this.mapper = mapper;
    this.dlq = dlq;
    this.metrics = metrics;
  }

  public void execute(UUID id, UUID token) {
    long processingStarted = System.nanoTime();
    var row =
        tx.execute(
            s -> {
              int claimed =
                  db.update(
                      "UPDATE webhook_deliveries SET status='PROCESSING',attempt_count=attempt_count+1,lease_until=now()+interval '90 seconds',updated_at=now() WHERE id=? AND lease_token=? AND lease_until>now() AND status IN ('PENDING','RETRY_SCHEDULED')",
                      id,
                      token);
              if (claimed == 0) return null;
              return db.queryForMap(
                  "SELECT d.*,e.event_type,e.payload,e.correlation_id,e.created_at AS event_created_at,w.url,w.active,w.deleted_at,w.signing_secret,w.previous_secret,w.previous_secret_expires_at,w.max_attempts,w.retry_base_seconds,w.retry_max_seconds FROM webhook_deliveries d JOIN events e ON e.id=d.event_id JOIN webhook_endpoints w ON w.id=d.endpoint_id WHERE d.id=?",
                  id);
            });
    if (row == null) return;
    Instant started = Instant.now();
    long nanos = System.nanoTime();
    Integer code = null;
    String error = null, signature = null;
    Long retryAfter = null;
    UUID tenant = (UUID) row.get("tenant_id");
    int attempt = ((Number) row.get("attempt_count")).intValue();
    try {
      if (!(Boolean) row.get("active") || row.get("deleted_at") != null)
        throw new ApiException(400, "Endpoint disabled or deleted");
      var body =
          mapper
              .createObjectNode()
              .put("id", row.get("event_id").toString())
              .put("deliveryId", id.toString())
              .put("eventType", row.get("event_type").toString())
              .put("createdAt", ((Timestamp) row.get("event_created_at")).toInstant().toString());
      body.set("payload", mapper.readTree(row.get("payload").toString()));
      byte[] bytes = mapper.writeValueAsBytes(body);
      String timestamp = Long.toString(Instant.now().getEpochSecond());
      signature =
          crypto.sign(crypto.decrypt(row.get("signing_secret").toString()), timestamp, bytes);
      if (row.get("previous_secret") != null
          && ((Timestamp) row.get("previous_secret_expires_at")).toInstant().isAfter(Instant.now()))
        signature +=
            ","
                + crypto.sign(
                    crypto.decrypt(row.get("previous_secret").toString()), timestamp, bytes);
      var post = new HttpPost(policy.validateForDelivery(row.get("url").toString()));
      post.setHeader("X-DispatchGrid-Event-Id", row.get("event_id").toString());
      post.setHeader("X-DispatchGrid-Delivery-Id", id.toString());
      post.setHeader("X-DispatchGrid-Timestamp", timestamp);
      post.setHeader("X-DispatchGrid-Signature", signature);
      post.setHeader("X-Correlation-Id", row.get("correlation_id").toString());
      post.setEntity(new ByteArrayEntity(bytes, ContentType.APPLICATION_JSON));
      // Watchdog enforces a total request deadline even if a peer trickles bytes indefinitely.
      try (var watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
        var cancellation = watchdog.schedule(post::cancel, 15, TimeUnit.SECONDS);
        try (var response = http.execute(post)) {
          code = response.getCode();
          var header = response.getFirstHeader("Retry-After");
          if (header != null)
            try {
              retryAfter = Math.min(86400L, Math.max(0, Long.parseLong(header.getValue())));
            } catch (NumberFormatException ignored) {
              /* HTTP-date hints are optional; exponential backoff remains authoritative. */
            }
          // Never consume or log the response body; an untrusted destination could stream without
          // bound.
        } finally {
          cancellation.cancel(false);
        }
      }
      if (code < 200 || code >= 300) error = "HTTP " + code;
    } catch (ApiException e) {
      code = 400;
      error = e.getMessage();
    } catch (Exception e) {
      error = e.getClass().getSimpleName();
    }
    long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos);
    Integer resultCode = code;
    String resultError = error, resultSignature = signature;
    Long resultRetryAfter = retryAfter;
    String outcome =
        tx.execute(
            s -> {
              var current =
                  db.queryForList(
                          "SELECT status,lease_token FROM webhook_deliveries WHERE id=? FOR UPDATE",
                          id)
                      .getFirst();
              if (!"PROCESSING".equals(current.get("status"))
                  || !token.equals(current.get("lease_token"))) return "STALE";
              String next =
                  resultCode != null && resultCode >= 200 && resultCode < 300
                      ? "DELIVERED"
                      : !RetryPolicy.retryable(resultCode)
                          ? "FAILED"
                          : attempt >= ((Number) row.get("max_attempts")).intValue()
                              ? "EXHAUSTED"
                              : "RETRY_SCHEDULED";
              long delay =
                  RetryPolicy.delay(
                          attempt,
                          ((Number) row.get("retry_base_seconds")).intValue(),
                          ((Number) row.get("retry_max_seconds")).intValue())
                      .toMillis();
              if (resultRetryAfter != null) delay = Math.max(delay, resultRetryAfter * 1000);
              db.update(
                  "INSERT INTO delivery_attempts(id,tenant_id,delivery_id,attempt_number,replay_number,status_code,latency_ms,outcome,error,signature,started_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                  UUID.randomUUID(),
                  tenant,
                  id,
                  attempt,
                  row.get("replay_count"),
                  resultCode,
                  latency,
                  next,
                  resultError,
                  resultSignature,
                  Timestamp.from(started));
              db.update(
                  "UPDATE webhook_deliveries SET status=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,last_status_code=?,last_error=?,delivered_at=?,updated_at=now() WHERE id=?",
                  next,
                  Timestamp.from(Instant.now().plusMillis(delay)),
                  resultCode,
                  resultError,
                  next.equals("DELIVERED") ? Timestamp.from(Instant.now()) : null,
                  id);
              if (next.equals("FAILED") || next.equals("EXHAUSTED"))
                dlq.record(
                    tenant,
                    id,
                    ((Number) row.get("replay_count")).intValue(),
                    resultError == null ? next : resultError);
              return next;
            });
    metrics.counter("dispatchgrid.deliveries", "outcome", outcome).increment();
    metrics.timer("dispatchgrid.delivery.duration").record(latency, TimeUnit.MILLISECONDS);
    metrics
        .timer("dispatchgrid.worker.duration")
        .record(System.nanoTime() - processingStarted, TimeUnit.NANOSECONDS);
    if ("RETRY_SCHEDULED".equals(outcome)) metrics.counter("dispatchgrid.retries").increment();
    if ("FAILED".equals(outcome) || "EXHAUSTED".equals(outcome))
      metrics.counter("dispatchgrid.deadletters").increment();
    try {
      MDC.put("tenantId", tenant.toString());
      MDC.put("eventId", row.get("event_id").toString());
      MDC.put("deliveryId", id.toString());
      MDC.put("correlationId", row.get("correlation_id").toString());
      LoggerFactory.getLogger(getClass())
          .info(
              "deliveryAttempt attemptNumber={} status={} latencyMs={}", attempt, outcome, latency);
    } finally {
      MDC.clear();
    }
  }
}
