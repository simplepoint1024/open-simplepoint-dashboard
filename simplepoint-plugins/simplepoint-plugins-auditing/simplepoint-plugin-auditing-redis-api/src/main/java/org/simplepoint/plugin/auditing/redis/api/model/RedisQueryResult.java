package org.simplepoint.plugin.auditing.redis.api.model;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.domain.Page;

/**
 * A page exists only after the complete scan succeeds; progress is never a partial result total.
 */
public record RedisQueryResult(
    Status status,
    String snapshotId,
    String pattern,
    Instant startedAt,
    Instant completedAt,
    Instant expiresAt,
    long observedKeys,
    int maxKeys,
    long maxEstimatedBytes,
    long scanTimeoutMillis,
    String message,
    Page<Map<String, Object>> page) {
  /** Terminal failures require explicit refresh; polling never silently starts another scan. */
  public enum Status {
    BUILDING,
    READY,
    EXPIRED,
    BUSY,
    LIMIT_EXCEEDED,
    TIMED_OUT,
    FAILED
  }
}
