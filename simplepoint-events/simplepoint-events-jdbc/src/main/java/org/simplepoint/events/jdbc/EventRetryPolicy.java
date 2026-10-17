/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.jdbc;

import java.time.Duration;

/** Bounded retry policy shared by broker delivery and local business processing. */
public record EventRetryPolicy(int maxAttempts, Duration lease, Duration initialDelay,
                               Duration maxDelay) {
  /** Rejects unbounded or non-positive retry settings. */
  public EventRetryPolicy {
    if (maxAttempts < 1 || maxAttempts > 100 || lease.toMillis() < 1000
        || initialDelay.isNegative() || initialDelay.isZero()
        || maxDelay.compareTo(initialDelay) < 0) {
      throw new IllegalArgumentException("Invalid event retry policy");
    }
  }

  /** Default bounded retries with a recoverable one-minute lease. */
  public static EventRetryPolicy defaults() {
    return new EventRetryPolicy(12, Duration.ofSeconds(60), Duration.ofSeconds(2),
        Duration.ofMinutes(5));
  }

  /** Exponential delay capped at the configured maximum. */
  public long delayMillis(int attempt) {
    return Math.min(maxDelay.toMillis(),
        initialDelay.toMillis() * (1L << Math.min(Math.max(attempt - 1, 0), 20)));
  }
}
