package io.dispatchgrid.common;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public final class RetryPolicy {
  private RetryPolicy() {}

  public static boolean retryable(Integer status) {
    return status == null || status == 408 || status == 425 || status == 429 || status >= 500;
  }

  public static Duration delay(int attempt, int baseSeconds, int maxSeconds) {
    return delay(attempt, baseSeconds, maxSeconds, ThreadLocalRandom.current().nextDouble());
  }

  public static Duration delay(int attempt, int baseSeconds, int maxSeconds, double jitter) {
    long exponential =
        Math.min(maxSeconds, (long) baseSeconds * (1L << Math.min(20, Math.max(0, attempt - 1))));
    return Duration.ofMillis(
        Math.min(
            maxSeconds * 1000L, Math.max(1, (long) (exponential * 1000 * (0.75 + jitter * 0.5)))));
  }
}
