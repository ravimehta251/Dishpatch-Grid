package io.dispatchgrid.common;

public class RateLimitException extends ApiException {
  private final long retryAfterSeconds;

  public RateLimitException(long retryAfterSeconds) {
    super(429, "Tenant ingestion quota exceeded");
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }

  public long getRetryAfterSeconds() {
    return retryAfterSeconds;
  }
}
