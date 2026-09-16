package org.simplepoint.plugin.ai.core.service.schedule;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Exponentially reduces empty durable-queue polls while keeping the configured
 * scheduler interval when work is available.
 */
public final class AiIdlePollBackoff {

  private static final Duration FALLBACK_INTERVAL = Duration.ofSeconds(1);

  private static final int MAXIMUM_SHIFT = 20;

  private final boolean enabled;

  private final long baseIntervalNanos;

  private final long maximumIntervalNanos;

  private final LongSupplier nanoTime;

  private int consecutiveIdlePolls;

  private long nextPollAtNanos;

  private boolean delayed;

  /** Creates a backoff using the monotonic system clock. */
  public AiIdlePollBackoff(
      final Duration baseInterval,
      final AiPollingProperties properties
  ) {
    this(baseInterval, properties, System::nanoTime);
  }

  AiIdlePollBackoff(
      final Duration baseInterval,
      final AiPollingProperties properties,
      final LongSupplier nanoTime
  ) {
    Duration normalizedBase = positive(baseInterval, FALLBACK_INTERVAL);
    Duration configuredMaximum = properties == null
        ? normalizedBase : positive(
            properties.getMaximumIdleInterval(),
            normalizedBase
        );
    this.enabled = properties != null
        && properties.isAdaptiveBackoffEnabled();
    this.baseIntervalNanos = normalizedBase.toNanos();
    this.maximumIntervalNanos = Math.max(
        baseIntervalNanos,
        configuredMaximum.toNanos()
    );
    this.nanoTime = nanoTime;
  }

  /** Returns whether this scheduler invocation should query its queue. */
  public synchronized boolean shouldPoll() {
    return !enabled
        || !delayed
        || nanoTime.getAsLong() - nextPollAtNanos >= 0L;
  }

  /** Records whether the latest successful poll found work. */
  public synchronized void recordResult(final boolean workFound) {
    if (!enabled || workFound) {
      consecutiveIdlePolls = 0;
      nextPollAtNanos = 0L;
      delayed = false;
      return;
    }
    consecutiveIdlePolls = Math.min(
        consecutiveIdlePolls + 1,
        MAXIMUM_SHIFT
    );
    long multiplier = 1L << consecutiveIdlePolls;
    long delay = saturatedMultiply(baseIntervalNanos, multiplier);
    nextPollAtNanos = nanoTime.getAsLong()
        + Math.min(maximumIntervalNanos, delay);
    delayed = true;
  }

  private static Duration positive(
      final Duration value,
      final Duration fallback
  ) {
    return value == null || value.isZero() || value.isNegative()
        ? fallback : value;
  }

  private static long saturatedMultiply(
      final long value,
      final long multiplier
  ) {
    if (value > Long.MAX_VALUE / multiplier) {
      return Long.MAX_VALUE;
    }
    return value * multiplier;
  }

}
