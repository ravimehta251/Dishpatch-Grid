package io.dispatchgrid.api;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import io.dispatchgrid.common.RateLimiter;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ApiIntegrationIT {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("spring.data.redis.host", redis::getHost);
    r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    r.add("dispatchgrid.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    r.add("dispatchgrid.jwt-secret", () -> "test-jwt-secret-at-least-thirty-two-bytes");
    r.add("dispatchgrid.monitoring-token", () -> "test-monitoring-at-least-thirty-two-bytes");
    r.add("dispatchgrid.allow-private-destinations", () -> "localhost");
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate db;
  @Autowired RateLimiter limiter;
  @Autowired io.micrometer.core.instrument.MeterRegistry metrics;

  String register() throws Exception {
    return json(mvc.perform(
                post("/api/v1/auth/register")
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(
                            Map.of(
                                "tenantName",
                                "Tests",
                                "email",
                                UUID.randomUUID() + "@example.test",
                                "password",
                                "secure-password-123"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString())
        .path("accessToken")
        .asText();
  }

  JsonNode json(String s) throws Exception {
    return mapper.readTree(s);
  }

  JsonNode createKey(String token) throws Exception {
    return json(
        mvc.perform(
                post("/api/v1/api-keys")
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .content("{\"name\":\"test\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  void authenticationIdempotencyIsolationAndRevocation() throws Exception {
    String owner = register(), other = register();
    JsonNode key = createKey(owner);
    String event = "{\"eventType\":\"order.created\",\"payload\":{\"a\":1,\"b\":2}}";
    var result =
        json(
            mvc.perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer " + key.path("key").asText())
                        .header("Idempotency-Key", "same")
                        .contentType("application/json")
                        .content(event))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String id = result.path("id").asText();
    mvc.perform(
            post("/api/v1/events")
                .header("Authorization", "Bearer " + key.path("key").asText())
                .header("Idempotency-Key", "same")
                .contentType("application/json")
                .content(event))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.duplicate").value(true));
    mvc.perform(
            post("/api/v1/events")
                .header("Authorization", "Bearer " + owner)
                .header("Idempotency-Key", "same")
                .contentType("application/json")
                .content(event.replace("\"a\":1", "\"a\":9")))
        .andExpect(status().isConflict());
    mvc.perform(get("/api/v1/events/" + id).header("Authorization", "Bearer " + other))
        .andExpect(status().isNotFound());
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE payload::jsonb->>'eventId'=?",
                Long.class,
                id))
        .isEqualTo(1);
    mvc.perform(
            get("/api/v1/api-keys").header("Authorization", "Bearer " + key.path("key").asText()))
        .andExpect(status().isForbidden());
    mvc.perform(
            delete("/api/v1/api-keys/" + key.path("id").asText())
                .header("Authorization", "Bearer " + owner))
        .andExpect(status().isNoContent());
    mvc.perform(
            post("/api/v1/events")
                .header("Authorization", "Bearer " + key.path("key").asText())
                .header("Idempotency-Key", "new")
                .contentType("application/json")
                .content(event))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void endpointIsolationSsrfAndEncryptedSecret() throws Exception {
    String owner = register(), other = register();
    String endpoint =
        "{\"url\":\"http://localhost:8090/webhook\",\"description\":\"test\",\"active\":true,\"subscribedEvents\":[\"order.created\"],\"maxAttempts\":3,\"retryBaseSeconds\":1,\"retryMaxSeconds\":2}";
    var result =
        json(
            mvc.perform(
                    post("/api/v1/endpoints")
                        .header("Authorization", "Bearer " + owner)
                        .contentType("application/json")
                        .content(endpoint))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String id = result.path("id").asText();
    assertThat(
            db.queryForObject(
                "SELECT signing_secret FROM webhook_endpoints WHERE id=?",
                String.class,
                UUID.fromString(id)))
        .startsWith("v1:")
        .doesNotContain(result.path("signingSecret").asText());
    mvc.perform(get("/api/v1/endpoints/" + id).header("Authorization", "Bearer " + other))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/endpoints")
                .header("Authorization", "Bearer " + owner)
                .contentType("application/json")
                .content(
                    endpoint.replace("http://localhost:8090/webhook", "https://169.254.169.254")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void concurrentRateLimitIsAtomic() throws Exception {
    UUID tenant = UUID.randomUUID();
    try (var pool = Executors.newFixedThreadPool(12)) {
      var results = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 40; i++)
        results.add(
            pool.submit(
                () -> {
                  try {
                    limiter.check(tenant, 10);
                    return true;
                  } catch (io.dispatchgrid.common.RateLimitException e) {
                    return false;
                  }
                }));
      int accepted = 0;
      for (var f : results) if (f.get()) accepted++;
      assertThat(accepted).isEqualTo(10);
    }
  }

  @Test
  void logoutRevokesRefreshEvenWhenAccessHeaderIsInvalid() throws Exception {
    var response =
        mvc.perform(
                post("/api/v1/auth/register")
                    .header("Authorization", "Bearer expired-or-invalid")
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(
                            Map.of(
                                "tenantName",
                                "Session tests",
                                "email",
                                UUID.randomUUID() + "@example.test",
                                "password",
                                "secure-password-123"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    String cookie = response.getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
    var refreshCookie = new jakarta.servlet.http.Cookie("dispatchgrid_refresh", cookie);
    mvc.perform(
            post("/api/v1/auth/logout")
                .cookie(refreshCookie)
                .header("Authorization", "Bearer expired-or-invalid"))
        .andExpect(status().isNoContent());
    mvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void outboxInsertFailureRollsBackAcceptedEvent() throws Exception {
    String token = register();
    long before = db.queryForObject("SELECT count(*) FROM events", Long.class);
    double metricBefore = metrics.counter("dispatchgrid.events.ingested").count();
    db.execute(
        "ALTER TABLE outbox_messages ADD CONSTRAINT test_reject_event_outbox CHECK (topic <> 'dispatchgrid.events') NOT VALID");
    try {
      mvc.perform(
              post("/api/v1/events")
                  .header("Authorization", "Bearer " + token)
                  .header("Idempotency-Key", UUID.randomUUID().toString())
                  .contentType("application/json")
                  .content("{\"eventType\":\"rollback.test\",\"payload\":{}}"))
          .andExpect(status().isConflict());
      assertThat(db.queryForObject("SELECT count(*) FROM events", Long.class)).isEqualTo(before);
      assertThat(metrics.counter("dispatchgrid.events.ingested").count()).isEqualTo(metricBefore);
    } finally {
      db.execute("ALTER TABLE outbox_messages DROP CONSTRAINT test_reject_event_outbox");
    }
  }

  @Test
  void concurrentIngestionCreatesOneEvent() throws Exception {
    String token = register(), key = UUID.randomUUID().toString();
    try (var pool = Executors.newFixedThreadPool(6)) {
      var futures = new ArrayList<Future<String>>();
      for (int i = 0; i < 6; i++)
        futures.add(
            pool.submit(
                () ->
                    mvc.perform(
                            post("/api/v1/events")
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", key)
                                .contentType("application/json")
                                .content("{\"eventType\":\"test\",\"payload\":{}}"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()));
      var ids = new HashSet<String>();
      for (var f : futures) ids.add(json(f.get()).path("id").asText());
      assertThat(ids).hasSize(1);
    }
  }
}
