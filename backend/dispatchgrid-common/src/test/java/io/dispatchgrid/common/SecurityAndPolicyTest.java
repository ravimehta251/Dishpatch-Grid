package io.dispatchgrid.common;

import static org.assertj.core.api.Assertions.*;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecurityAndPolicyTest {
  @Test
  void temporaryDnsFailuresRemainRetryableButUnsafeAddressesAreTerminal() throws Exception {
    var unavailable =
        new DestinationPolicy("") {
          @Override
          public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
            throw new java.net.UnknownHostException("Temporary resolver failure");
          }
        };
    assertThatThrownBy(() -> unavailable.validateForDelivery("https://example.com/webhook"))
        .isInstanceOf(java.net.UnknownHostException.class);
    assertThatThrownBy(() -> unavailable.validate("https://example.com/webhook"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> new DestinationPolicy("").validateForDelivery("https://127.0.0.1/webhook"))
        .isInstanceOf(ApiException.class);
  }

  final CryptoService crypto = new CryptoService(Base64.getEncoder().encodeToString(new byte[32]));

  @Test
  void encryptionIsRandomizedAndAuthenticated() {
    String a = crypto.encrypt("secret"), b = crypto.encrypt("secret");
    assertThat(a).isNotEqualTo(b).doesNotContain("secret");
    assertThat(crypto.decrypt(a)).isEqualTo("secret");
    assertThatThrownBy(() -> crypto.decrypt(a.substring(0, a.length() - 4) + "AAAA"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void signaturesAuthenticateExactBytesAndFreshness() {
    var now = Instant.now();
    String timestamp = "" + now.getEpochSecond();
    byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
    String signature = crypto.sign("secret", timestamp, body);
    assertThat(crypto.verify("secret", timestamp, body, signature, now)).isTrue();
    assertThat(crypto.verify("wrong", timestamp, body, signature, now)).isFalse();
    assertThat(crypto.verify("secret", timestamp, body, signature, now.plusSeconds(301))).isFalse();
    assertThat(crypto.verify("secret", timestamp, "{}".getBytes(), signature, now)).isFalse();
    assertThat(crypto.verify("secret", "9223372036854775807", body, signature, now)).isFalse();
  }

  @Test
  void unsafeAddressesRejected() throws Exception {
    for (String ip :
        new String[] {
          "127.0.0.1",
          "10.1.1.1",
          "169.254.169.254",
          "100.64.0.1",
          "192.168.1.2",
          "::1",
          "fc00::1",
          "2001:db8::1",
          "198.18.0.1"
        }) assertThat(DestinationPolicy.isPublic(InetAddress.getByName(ip))).as(ip).isFalse();
    assertThat(DestinationPolicy.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
  }

  @Test
  void urlsRequireHttpsAndRejectUserInfo() {
    var policy = new DestinationPolicy("");
    for (String url :
        new String[] {
          "http://example.com",
          "https://127.0.0.1",
          "https://user:pass@example.com",
          "file:///etc/passwd",
          "https://169.254.169.254/latest/meta-data"
        }) assertThatThrownBy(() -> policy.validate(url)).isInstanceOf(ApiException.class);
    assertThat(
            new DestinationPolicy("localhost").validate("http://localhost:8090/webhook").getHost())
        .isEqualTo("localhost");
  }

  @Test
  void retryClassificationAndCap() {
    assertThat(RetryPolicy.retryable(429)).isTrue();
    assertThat(RetryPolicy.retryable(503)).isTrue();
    assertThat(RetryPolicy.retryable(null)).isTrue();
    assertThat(RetryPolicy.retryable(400)).isFalse();
    assertThat(RetryPolicy.retryable(302)).isFalse();
    assertThat(RetryPolicy.delay(50, 10, 600, 1).toSeconds()).isEqualTo(600);
    assertThat(RetryPolicy.delay(1, 10, 600, 0).toMillis()).isEqualTo(7500);
  }

  @Test
  void transitionsAreBounded() {
    assertThat(DeliveryStatus.DELIVERED.canTransitionTo(DeliveryStatus.PENDING)).isFalse();
    assertThat(DeliveryStatus.EXHAUSTED.canTransitionTo(DeliveryStatus.PENDING)).isTrue();
    assertThat(DeliveryStatus.PROCESSING.canTransitionTo(DeliveryStatus.RETRY_SCHEDULED)).isTrue();
  }
}
