package io.dispatchgrid.worker;

import io.dispatchgrid.common.DestinationPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class WorkerConfiguration {
  @Bean
  KafkaAdmin.NewTopics topics(
      @Value("${dispatchgrid.partitions:12}") int partitions,
      @Value("${dispatchgrid.replication-factor:1}") int replicas) {
    return new KafkaAdmin.NewTopics(
        topic("dispatchgrid.events", partitions, replicas),
        topic("dispatchgrid.delivery.jobs", partitions, replicas),
        topic("dispatchgrid.delivery.dlq", partitions, replicas));
  }

  private NewTopic topic(String name, int partitions, int replicas) {
    return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
  }

  @Bean
  DefaultErrorHandler kafkaErrorHandler() {
    // DB/broker outages must never discard an accepted event. Malformed envelopes are durably
    // quarantined by listeners.
    return new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
  }

  @Bean(destroyMethod = "close")
  CloseableHttpClient outboundClient(
      DestinationPolicy policy, @Value("${dispatchgrid.concurrency:8}") int concurrency) {
    var manager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(concurrency)
            .setMaxConnPerRoute(Math.min(4, concurrency))
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(3))
                    .setSocketTimeout(Timeout.ofSeconds(10))
                    .build())
            .setDnsResolver(
                new DnsResolver() {
                  @Override
                  public InetAddress[] resolve(String host) throws UnknownHostException {
                    return policy.resolve(host);
                  }

                  @Override
                  public String resolveCanonicalHostname(String host) throws UnknownHostException {
                    policy.resolve(host);
                    return host;
                  }
                })
            .build();
    return HttpClients.custom()
        .setConnectionManager(manager)
        .disableRedirectHandling()
        .disableAutomaticRetries()
        .disableCookieManagement()
        .setConnectionReuseStrategy((request, response, context) -> false)
        .setDefaultRequestConfig(
            RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(2))
                .setResponseTimeout(Timeout.ofSeconds(10))
                .build())
        .build();
  }

  @Bean
  SecurityFilterChain monitoringSecurity(
      HttpSecurity http, @Value("${dispatchgrid.monitoring-token}") String token) throws Exception {
    if (token.length() < 32)
      throw new IllegalArgumentException("MONITORING_TOKEN must have at least 32 characters");
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            s ->
                s.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/actuator/prometheus")
                    .access(
                        (authentication, context) -> {
                          String supplied = context.getRequest().getHeader("Authorization");
                          return new org.springframework.security.authorization
                              .AuthorizationDecision(
                              supplied != null
                                  && MessageDigest.isEqual(
                                      ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                                      supplied.getBytes(StandardCharsets.UTF_8)));
                        })
                    .anyRequest()
                    .denyAll())
        .build();
  }
}
