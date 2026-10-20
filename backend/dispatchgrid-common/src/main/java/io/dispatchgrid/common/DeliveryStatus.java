package io.dispatchgrid.common;

public enum DeliveryStatus {
  PENDING,
  PROCESSING,
  RETRY_SCHEDULED,
  DELIVERED,
  FAILED,
  EXHAUSTED;

  public boolean terminal() {
    return this == DELIVERED || this == FAILED || this == EXHAUSTED;
  }

  public boolean canTransitionTo(DeliveryStatus target) {
    return switch (this) {
      case PENDING, RETRY_SCHEDULED -> target == PROCESSING || target == FAILED;
      case PROCESSING ->
          target == DELIVERED
              || target == RETRY_SCHEDULED
              || target == FAILED
              || target == EXHAUSTED;
      case FAILED, EXHAUSTED -> target == PENDING;
      case DELIVERED -> false;
    };
  }
}
