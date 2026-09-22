package org.simplepoint.plugin.auditing.redis.service.query;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.simplepoint.core.jackson.FieldPermissionPolicy;
import org.simplepoint.core.query.CollectionQuery;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryMetadata;
import org.simplepoint.plugin.auditing.redis.api.model.RedisQueryResult;
import org.simplepoint.plugin.auditing.redis.api.model.RedisQueryResult.Status;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Bounded process-local metadata snapshots for globally authorized Redis administrators.
 * Authorization and projection are evaluated per request, never captured on the worker.
 */
@Service
public final class RedisEntryQueryService implements DisposableBean {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(RedisEntryQueryService.class);
  private final RedisMetadataScanner scanner;
  private final Limits limits;
  private final Clock clock;
  private final ExecutorService worker;
  private final java.util.concurrent.ScheduledExecutorService deadlines =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "redis-metadata-deadline");
            thread.setDaemon(true);
            return thread;
          });
  private final Map<String, Snapshot> snapshots = new LinkedHashMap<>(8, 0.75f, true);
  private Snapshot active;
  private boolean closed;

  /** Configuration bounds the aggregate cache and permits only one active Redis scan. */
  @Autowired
  public RedisEntryQueryService(RedisMetadataScanner scanner, Environment environment) {
    this(
        scanner,
        new Limits(
            environment.getProperty("simplepoint.redis.query.max-keys", Integer.class, 100000),
            environment.getProperty(
                "simplepoint.redis.query.max-estimated-bytes", Long.class, 33554432L),
            environment.getProperty("simplepoint.redis.query.max-snapshots", Integer.class, 2),
            Duration.ofSeconds(
                environment.getProperty(
                    "simplepoint.redis.query.scan-timeout-seconds", Long.class, 60L)),
            Duration.ofSeconds(
                environment.getProperty(
                    "simplepoint.redis.query.retention-seconds", Long.class, 300L))),
        Clock.systemUTC(),
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "redis-metadata-scan");
              thread.setDaemon(true);
              return thread;
            }));
  }

  RedisEntryQueryService(
      RedisMetadataScanner scanner, Limits limits, Clock clock, ExecutorService worker) {
    this.scanner = scanner;
    this.limits = limits;
    this.clock = clock;
    this.worker = worker;
  }

  /** Compiles all user conditions before scanning and applies them before paging and counting. */
  public RedisQueryResult query(
      Map<String, String> filters,
      Pageable requested,
      String pattern,
      String snapshotId,
      boolean refresh) {
    requireIdentity();
    Pageable pageable = requested == null ? PageRequest.of(0, 20) : requested;
    if (pageable.isUnpaged()) {
      throw new InvalidQueryException("Redis 查询必须分页");
    }
    var prepared =
        CollectionQuery.prepare(
            RedisEntryMetadata.class,
            filters,
            pageable,
            Comparator.comparing(RedisEntryMetadata::key));
    String scope = pattern == null || pattern.isEmpty() ? "*" : pattern;
    if (scope.length() > 512 || (snapshotId != null && snapshotId.length() > 64)) {
      throw new InvalidQueryException("扫描范围或快照标识过长");
    }
    Selection selection = select(scope, snapshotId, refresh);
    return selection.status == null
        ? response(selection.snapshot, prepared)
        : result(selection.status, selection.snapshot, scope, selection.message, null);
  }

  private synchronized Selection select(String scope, String snapshotId, boolean refresh) {
    if (closed) {
      return new Selection(null, Status.FAILED, "查询服务正在关闭");
    }
    Snapshot snapshot = snapshots.get(scope);
    boolean matches = snapshot != null && snapshot.id.equals(snapshotId);
    // A token is a version pin, not a request to silently substitute a different snapshot.
    if (snapshotId != null && !matches && !refresh) {
      return new Selection(null, Status.EXPIRED, "扫描结果已过期或失效，请刷新");
    }
    if (snapshot != null && snapshot.state == Status.BUILDING) {
      if (snapshot.timedOut || !clock.instant().isBefore(snapshot.startedAt.plus(limits.scanTimeout()))) {
        return new Selection(snapshot, Status.TIMED_OUT, "扫描超时，请缩小键名模式后重试");
      }
      return new Selection(snapshot, null, null);
    }
    boolean expired = snapshot != null && !clock.instant().isBefore(snapshot.expiresAt);
    if (snapshot != null && !expired && (!refresh || (snapshotId != null && !matches))) {
      return new Selection(snapshot, null, null);
    }
    if (snapshotId != null && !refresh) {
      return new Selection(snapshot, Status.EXPIRED, "扫描结果已过期，请刷新");
    }
    if (active != null) {
      return new Selection(null, Status.BUSY, "另一个扫描正在运行，请稍后重试");
    }
    while (snapshots.size() >= limits.maxSnapshots() && !snapshots.containsKey(scope)) {
      snapshots.remove(snapshots.keySet().iterator().next());
    }
    snapshot = new Snapshot(scope, clock.instant());
    snapshots.put(scope, snapshot);
    active = snapshot;
    Snapshot task = snapshot;
    try {
      worker.execute(() -> scan(task));
    } catch (java.util.concurrent.RejectedExecutionException error) {
      active = null;
      snapshot.state = Status.FAILED;
      snapshot.message = "查询服务不可用，请重试";
      snapshot.expiresAt = clock.instant().plus(limits.retention());
    }
    return new Selection(snapshot, null, null);
  }

  /** No row identity, ordering or keyspace total may bypass a hidden/masked key policy. */
  public static void requireIdentity() {
    RedisEntryFieldPolicy.requireIdentity();
    if (!QueryCapabilities.readable(RedisEntryMetadata.class, "key")) {
      throw new AccessDeniedException("无权查看 Redis 键");
    }
  }

  /** Mutations invalidate both completed and in-flight snapshots without starting Redis work. */
  public synchronized void invalidate() {
    snapshots.clear();
    if (active != null) {
      active.invalidated = true;
    }
  }

  private RedisQueryResult response(
      Snapshot snapshot, CollectionQuery.Prepared<RedisEntryMetadata> prepared) {
    // The volatile state publishes the immutable rows and completion timestamps.
    Status state = snapshot.state;
    Page<Map<String, Object>> page =
        state == Status.READY ? prepared.page(snapshot.rows).map(this::project) : null;
    return result(state, snapshot, snapshot.pattern, snapshot.message, page);
  }

  private RedisQueryResult result(
      Status status,
      Snapshot snapshot,
      String pattern,
      String message,
      Page<Map<String, Object>> page) {
    return new RedisQueryResult(
        status,
        snapshot == null ? null : snapshot.id,
        pattern,
        snapshot == null ? null : snapshot.startedAt,
        snapshot == null ? null : snapshot.completedAt,
        snapshot == null ? null : snapshot.expiresAt,
        snapshot == null ? 0 : snapshot.observedKeys,
        limits.maxKeys(),
        limits.maxEstimatedBytes(),
        limits.scanTimeout().toMillis(),
        message,
        page);
  }

  private Map<String, Object> project(RedisEntryMetadata row) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("key", row.key());
    values.put("type", row.type());
    values.put("ttlSeconds", row.ttlSeconds());
    values.put("persistent", row.persistent());
    values.put("size", row.size());
    values.put("editable", row.editable());
    values.put("observedAt", row.observedAt());
    return FieldPermissionPolicy.project(RedisEntryMetadata.class, values);
  }

  private void scan(Snapshot snapshot) {
    Map<String, RedisEntryMetadata> rows = new LinkedHashMap<>();
    long[] estimatedBytes = {0};
    Status status = Status.FAILED;
    String message = "Redis 元数据扫描失败，请重试";
    java.util.concurrent.ScheduledFuture<?> deadline = null;
    try {
      Thread scannerThread = Thread.currentThread();
      long remainingMillis =
          Math.max(
              0,
              Duration.between(clock.instant(), snapshot.startedAt.plus(limits.scanTimeout()))
                  .toMillis());
      deadline =
          deadlines.schedule(
              () -> {
                synchronized (this) {
                  if (active == snapshot) {
                    snapshot.timedOut = true;
                    scannerThread.interrupt();
                  }
                }
              },
              remainingMillis,
              java.util.concurrent.TimeUnit.MILLISECONDS);
      checkpoint(snapshot);
      scanner.scan(
          snapshot.pattern,
          row -> {
            checkpoint(snapshot);
            if (!rows.containsKey(row.key())) {
              long bytes = 256L + (long) row.key().length() * 2;
              if (rows.size() >= limits.maxKeys()
                  || estimatedBytes[0] + bytes > limits.maxEstimatedBytes()) {
                throw new ScanStopped(Status.LIMIT_EXCEEDED, "扫描结果超出资源预算，请缩小键名模式后重试");
              }
              estimatedBytes[0] += bytes;
              rows.put(row.key(), row);
              snapshot.observedKeys = rows.size();
            }
          },
          () -> checkpoint(snapshot));
      checkpoint(snapshot);
      snapshot.rows = List.copyOf(rows.values());
      status = Status.READY;
      message = null;
    } catch (ScanStopped stopped) {
      status = stopped.status;
      message = stopped.getMessage();
    } catch (RuntimeException error) {
      status = snapshot.timedOut ? Status.TIMED_OUT : Status.FAILED;
      message = snapshot.timedOut ? "扫描超时，请缩小键名模式后重试" : "Redis 元数据扫描失败，请重试或检查服务连接与读取权限";
      // Exception messages can include Redis keys or connection credentials.
      LOG.warn("Redis metadata scan {} failed ({})", snapshot.id, error.getClass().getSimpleName());
    } finally {
      synchronized (this) {
        if (deadline != null) {
          deadline.cancel(false);
        }
        if (snapshot.timedOut) {
          status = Status.TIMED_OUT;
          message = "扫描超时，请缩小键名模式后重试";
        }
        if (status != Status.READY || snapshot.invalidated) {
          snapshot.rows = List.of();
        }
        snapshot.message = message;
        snapshot.completedAt = clock.instant();
        snapshot.expiresAt = snapshot.completedAt.plus(limits.retention());
        snapshot.state = snapshot.invalidated ? Status.EXPIRED : status;
        if (active == snapshot) {
          active = null;
        }
      }
    }
  }

  private void checkpoint(Snapshot snapshot) {
    if (snapshot.timedOut) {
      throw new ScanStopped(Status.TIMED_OUT, "扫描超时，请缩小键名模式后重试");
    }
    if (snapshot.invalidated || Thread.currentThread().isInterrupted()) {
      throw new ScanStopped(Status.EXPIRED, "扫描结果已失效，请刷新");
    }
    if (!clock.instant().isBefore(snapshot.startedAt.plus(limits.scanTimeout()))) {
      throw new ScanStopped(Status.TIMED_OUT, "扫描超时，请缩小键名模式后重试");
    }
  }

  @Override
  public synchronized void destroy() {
    closed = true;
    invalidate();
    worker.shutdownNow();
    deadlines.shutdownNow();
  }

  record Limits(
      int maxKeys,
      long maxEstimatedBytes,
      int maxSnapshots,
      Duration scanTimeout,
      Duration retention) {
    Limits {
      if (maxKeys < 1
          || maxEstimatedBytes < 256
          || maxSnapshots < 1
          || scanTimeout.isNegative()
          || scanTimeout.isZero()
          || retention.isNegative()
          || retention.isZero()) {
        throw new IllegalArgumentException("Redis query budgets must be positive");
      }
    }
  }

  private static final class Snapshot {
    private final String id = UUID.randomUUID().toString();
    private final String pattern;
    private final Instant startedAt;
    private volatile Status state = Status.BUILDING;
    private volatile long observedKeys;
    private volatile boolean invalidated;
    private volatile boolean timedOut;
    private List<RedisEntryMetadata> rows = List.of();
    private Instant completedAt;
    private Instant expiresAt;
    private String message;

    private Snapshot(String pattern, Instant startedAt) {
      this.pattern = pattern;
      this.startedAt = startedAt;
    }
  }

  private record Selection(Snapshot snapshot, Status status, String message) {}

  private static final class ScanStopped extends RuntimeException {
    private final Status status;

    private ScanStopped(Status status, String message) {
      super(message);
      this.status = status;
    }
  }
}
