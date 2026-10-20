package io.dispatchgrid.worker;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.dispatchgrid.common.CryptoService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    properties = {"dispatchgrid.scheduling-enabled=false", "dispatchgrid.listeners-enabled=false"})
@Testcontainers
class WorkerIntegrationIT {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @Container
  static org.testcontainers.kafka.KafkaContainer kafka =
      new org.testcontainers.kafka.KafkaContainer("apache/kafka:3.9.1");

  static WireMockServer receiver = new WireMockServer(0);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("spring.data.redis.host", redis::getHost);
    r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    r.add("dispatchgrid.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    r.add("dispatchgrid.monitoring-token", () -> "test-monitoring-at-least-thirty-two-bytes");
    r.add("dispatchgrid.allow-private-destinations", () -> "localhost");
  }

  @Autowired JdbcTemplate db;
  @Autowired CryptoService crypto;
  @Autowired EventRouter router;
  @Autowired DeliveryScheduler scheduler;
  @Autowired DeliveryExecutor executor;
  @Autowired OutboxPublisher publisher;
  @Autowired org.springframework.kafka.config.KafkaListenerEndpointRegistry listeners;

  @BeforeAll
  static void start() {
    receiver.start();
  }

  @AfterAll
  static void stop() {
    receiver.stop();
  }

  @BeforeEach
  void clean() {
    db.execute("TRUNCATE tenants CASCADE");
    db.execute("TRUNCATE outbox_messages");
    receiver.resetAll();
  }

  UUID fixture(int max) {
    UUID tenant = UUID.randomUUID(), event = UUID.randomUUID(), endpoint = UUID.randomUUID();
    db.update("INSERT INTO tenants(id,name) VALUES(?,'test')", tenant);
    db.update(
        "INSERT INTO events(id,tenant_id,event_type,payload,correlation_id) VALUES(?,?,'order.created','{}','test')",
        event,
        tenant);
    db.update(
        "INSERT INTO webhook_endpoints(id,tenant_id,url,signing_secret,max_attempts,retry_base_seconds,retry_max_seconds) VALUES(?,?,?,?,?,1,1)",
        endpoint,
        tenant,
        receiver.baseUrl() + "/webhook",
        crypto.encrypt("secret"),
        max);
    db.update(
        "INSERT INTO webhook_subscriptions(endpoint_id,event_type) VALUES(?,'order.created')",
        endpoint);
    router.route(event);
    router.route(event);
    assertThat(db.queryForObject("SELECT count(*) FROM webhook_deliveries", Long.class))
        .isEqualTo(1);
    return db.queryForObject("SELECT id FROM webhook_deliveries", UUID.class);
  }

  UUID claim(UUID id) {
    scheduler.scheduleBatch();
    return db.queryForObject(
        "SELECT lease_token FROM webhook_deliveries WHERE id=?", UUID.class, id);
  }

  String state(UUID id) {
    return db.queryForObject("SELECT status FROM webhook_deliveries WHERE id=?", String.class, id);
  }

  void due(UUID id) {
    db.update(
        "UPDATE webhook_deliveries SET next_attempt_at=now()-interval '1 second' WHERE id=?", id);
  }

  @Test
  void signedSuccessAndDuplicateJob() throws Exception {
    receiver.stubFor(post("/webhook").willReturn(ok()));
    UUID id = fixture(3), lease = claim(id);
    executor.execute(id, lease);
    executor.execute(id, lease);
    assertThat(state(id)).isEqualTo("DELIVERED");
    receiver.verify(1, postRequestedFor(urlEqualTo("/webhook")));
    var request = receiver.getAllServeEvents().getFirst().getRequest();
    assertThat(
            crypto.verify(
                "secret",
                request.getHeader("X-DispatchGrid-Timestamp"),
                request.getBody(),
                request.getHeader("X-DispatchGrid-Signature"),
                java.time.Instant.now()))
        .isTrue();
  }

  @Test
  void retryThenSuccess() {
    receiver.stubFor(post("/webhook").willReturn(serverError()));
    UUID id = fixture(3);
    executor.execute(id, claim(id));
    assertThat(state(id)).isEqualTo("RETRY_SCHEDULED");
    receiver.stubFor(post("/webhook").willReturn(ok()));
    due(id);
    executor.execute(id, claim(id));
    assertThat(state(id)).isEqualTo("DELIVERED");
    assertThat(db.queryForObject("SELECT count(*) FROM delivery_attempts", Long.class))
        .isEqualTo(2);
  }

  @Test
  void terminalFailureAndExhaustionAreDurable() {
    receiver.stubFor(post("/webhook").willReturn(serverError()));
    UUID id = fixture(1);
    executor.execute(id, claim(id));
    assertThat(state(id)).isEqualTo("EXHAUSTED");
    assertThat(db.queryForObject("SELECT count(*) FROM dead_letters", Long.class)).isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE topic='dispatchgrid.delivery.dlq'",
                Long.class))
        .isEqualTo(1);
  }

  @Test
  void permanentClientFailureDoesNotRetry() {
    receiver.stubFor(post("/webhook").willReturn(aResponse().withStatus(400)));
    UUID id = fixture(5);
    executor.execute(id, claim(id));
    assertThat(state(id)).isEqualTo("FAILED");
  }

  @Test
  void abandonedLeaseRecoversWithFencedOldWorker() {
    UUID id = fixture(3), old = claim(id);
    db.update(
        "UPDATE webhook_deliveries SET status='PROCESSING',attempt_count=1,lease_until=now()-interval '1 second' WHERE id=?",
        id);
    scheduler.scheduleBatch();
    UUID fresh =
        db.queryForObject("SELECT lease_token FROM webhook_deliveries WHERE id=?", UUID.class, id);
    assertThat(fresh).isNotEqualTo(old);
    executor.execute(id, old);
    assertThat(state(id)).isEqualTo("RETRY_SCHEDULED");
    receiver.stubFor(post("/webhook").willReturn(ok()));
    executor.execute(id, fresh);
    assertThat(state(id)).isEqualTo("DELIVERED");
    assertThat(
            db.queryForObject(
                "SELECT outcome FROM delivery_attempts WHERE attempt_number=1", String.class))
        .isEqualTo("ABANDONED");
  }

  @Test
  void concurrentWorkersOnlyOneHttpAttempt() throws Exception {
    receiver.stubFor(post("/webhook").willReturn(ok()));
    UUID id = fixture(3), lease = claim(id);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Future<?>>();
      for (int i = 0; i < 4; i++) tasks.add(pool.submit(() -> executor.execute(id, lease)));
      for (var f : tasks) f.get();
    }
    receiver.verify(1, postRequestedFor(urlEqualTo("/webhook")));
    assertThat(state(id)).isEqualTo("DELIVERED");
  }

  @Test
  void outboxPublishesAfterBrokerAcknowledgment() {
    fixture(3);
    scheduler.scheduleBatch();
    publisher.publishBatch();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE published_at IS NULL", Long.class))
        .isZero();
  }

  @Test
  void abandonedOutboxClaimIsRecovered() {
    fixture(3);
    scheduler.scheduleBatch();
    db.update(
        "UPDATE outbox_messages SET lease_until=now()-interval '1 second',lease_token=?",
        UUID.randomUUID());
    publisher.publishBatch();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE published_at IS NOT NULL", Long.class))
        .isEqualTo(1);
  }

  @Test
  void responseTimeoutIsRetriedAndRecovers() {
    receiver.stubFor(post("/webhook").willReturn(ok().withFixedDelay(17000)));
    UUID id = fixture(3);
    executor.execute(id, claim(id));
    assertThat(state(id)).isEqualTo("RETRY_SCHEDULED");
    receiver.stubFor(post("/webhook").willReturn(ok()));
    due(id);
    executor.execute(id, claim(id));
    assertThat(state(id)).isEqualTo("DELIVERED");
  }

  @Test
  void twoSchedulersCreateOnlyOneLeasedJob() throws Exception {
    UUID id = fixture(3);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(scheduler::scheduleBatch);
      var b = pool.submit(scheduler::scheduleBatch);
      a.get();
      b.get();
    }
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE topic='dispatchgrid.delivery.jobs'",
                Long.class))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT lease_token FROM webhook_deliveries WHERE id=?", UUID.class, id))
        .isNotNull();
  }

  @Test
  void queuedJobSurvivesConsumerRestartAndDuplicateBrokerPublish() {
    receiver.stubFor(post("/webhook").willReturn(ok()));
    UUID id = fixture(3);
    scheduler.scheduleBatch();
    publisher.publishBatch();
    listeners.start();
    try {
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(30))
          .until(() -> state(id).equals("DELIVERED"));
      listeners.stop();
      db.update(
          "UPDATE outbox_messages SET published_at=NULL WHERE topic='dispatchgrid.delivery.jobs'");
      publisher.publishBatch();
      listeners.start();
      org.awaitility.Awaitility.await()
          .during(java.time.Duration.ofSeconds(2))
          .atMost(java.time.Duration.ofSeconds(10))
          .untilAsserted(() -> receiver.verify(1, postRequestedFor(urlEqualTo("/webhook"))));
    } finally {
      listeners.stop();
    }
  }
}
