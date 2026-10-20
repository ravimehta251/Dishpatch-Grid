package io.dispatchgrid.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "dispatchgrid.scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ScheduledWork {
  private final OutboxPublisher publisher;
  private final DeliveryScheduler scheduler;

  public ScheduledWork(OutboxPublisher publisher, DeliveryScheduler scheduler) {
    this.publisher = publisher;
    this.scheduler = scheduler;
  }

  @Scheduled(fixedDelayString = "${dispatchgrid.outbox-poll-ms:20}")
  public void publish() {
    publisher.publishBatch();
  }

  @Scheduled(fixedDelay = 500)
  public void schedule() {
    scheduler.scheduleBatch();
  }

  @org.springframework.context.annotation.Bean
  org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler() {
    var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("durable-scheduler-");
    return scheduler;
  }
}
