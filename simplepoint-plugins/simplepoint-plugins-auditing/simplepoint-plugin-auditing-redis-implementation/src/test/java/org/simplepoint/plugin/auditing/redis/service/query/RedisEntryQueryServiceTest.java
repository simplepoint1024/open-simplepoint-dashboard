package org.simplepoint.plugin.auditing.redis.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryMetadata;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryType;
import org.simplepoint.plugin.auditing.redis.api.model.RedisQueryResult;
import org.simplepoint.plugin.auditing.redis.api.model.RedisQueryResult.Status;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;

class RedisEntryQueryServiceTest {
  private ManualWorker worker = new ManualWorker();
  private final MutableClock clock = new MutableClock();
  private final AtomicInteger scans = new AtomicInteger();
  private RedisEntryQueryService service;

  private void setup(int maxKeys, long maxBytes, RedisMetadataScanner scanner) {
    if (service != null) service.destroy();
    worker = new ManualWorker();
    service =
        new RedisEntryQueryService(
            (pattern, accept, checkpoint) -> {
              scans.incrementAndGet();
              scanner.scan(pattern, accept, checkpoint);
            },
            new RedisEntryQueryService.Limits(
                maxKeys, maxBytes, 2, Duration.ofSeconds(10), Duration.ofSeconds(60)),
            clock,
            worker);
  }

  private RedisEntryMetadata row(String key, long size) {
    return new RedisEntryMetadata(
        key, RedisEntryType.STRING, null, true, size, true, clock.instant());
  }

  private RedisQueryResult query(String token, boolean refresh) {
    return service.query(Map.of(), PageRequest.of(0, 20), "*", token, refresh);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (service != null) service.destroy();
  }

  @Test
  void fullScopeFilteringSortingAndCountReuseOneCompletedScan() {
    setup(
        1000,
        1000000,
        (pattern, accept, checkpoint) -> {
          for (int i = 0; i < 520; i++) accept.accept(row("key-" + String.format("%03d", i), i));
        });
    var started = query(null, false);
    assertThat(started.status()).isEqualTo(Status.BUILDING);
    assertThat(started.page()).isNull();
    assertThat(query(started.snapshotId(), false).snapshotId()).isEqualTo(started.snapshotId());
    assertThat(worker.tasks).hasSize(1);
    assertThat(service.query(Map.of(), PageRequest.of(0, 20), "other:*", null, false).status())
        .isEqualTo(Status.BUSY);
    worker.runNext();
    var ready =
        service.query(
            Map.of(),
            PageRequest.of(25, 20, Sort.by("size").descending()),
            "*",
            started.snapshotId(),
            false);
    assertThat(ready.page().getTotalElements()).isEqualTo(520);
    assertThat(ready.page().getContent().getFirst().get("size")).isEqualTo(19L);
    var filtered =
        service.query(
            Map.of(
                "$query",
                """
                {"version":1,"where":{"op":"or","children":[
                  {"field":"key","op":"equals","value":"key-519"},
                  {"op":"not","children":[{"field":"size","op":"than:equal:less","value":517}]}]}}
                """,
                "key",
                "like:519"),
            PageRequest.of(0, 10),
            "*",
            started.snapshotId(),
            false);
    assertThat(filtered.page().getTotalElements()).isEqualTo(1);
    assertThat(filtered.page().getContent().getFirst().get("key")).isEqualTo("key-519");
    assertThat(scans).hasValue(1);
  }

  @Test
  void duplicateKeysDoNotConsumeTheBudgetOrInflateCount() {
    setup(
        2,
        10000,
        (pattern, accept, checkpoint) -> {
          accept.accept(row("a", 1));
          accept.accept(row("a", 9));
          accept.accept(row("b", 2));
        });
    var started = query(null, false);
    worker.runNext();
    var ready = query(started.snapshotId(), false);
    assertThat(ready.status()).isEqualTo(Status.READY);
    assertThat(ready.observedKeys()).isEqualTo(2);
    assertThat(ready.page().getTotalElements()).isEqualTo(2);
    assertThat(ready.page().getContent().getFirst().get("size")).isEqualTo(1L);
  }

  @Test
  void keyAndMemoryLimitsFailWithoutPublishingPartialPagesOrAutoRetrying() {
    for (long bytes : List.of(10000L, 256L)) {
      setup(
          1,
          bytes,
          (pattern, accept, checkpoint) -> {
            accept.accept(row("a", 1));
            accept.accept(row("b", 2));
          });
      var started = query(null, false);
      worker.runNext();
      var failed = query(started.snapshotId(), false);
      assertThat(failed.status()).isEqualTo(Status.LIMIT_EXCEEDED);
      assertThat(failed.page()).isNull();
      assertThat(query(null, false).snapshotId()).isEqualTo(started.snapshotId());
      assertThat(worker.tasks).isEmpty();
    }
  }

  @Test
  void timeoutAndConnectionFailureNeverPublishAnEmptySuccessfulPage() {
    setup(
        10,
        10000,
        (pattern, accept, checkpoint) -> {
          accept.accept(row("a", 1));
          clock.advance(Duration.ofSeconds(11));
          checkpoint.run();
        });
    var started = query(null, false);
    worker.runNext();
    assertThat(query(started.snapshotId(), false).status()).isEqualTo(Status.TIMED_OUT);
    assertThat(query(started.snapshotId(), false).page()).isNull();
    setup(
        10,
        10000,
        (pattern, accept, checkpoint) -> {
          throw new IllegalStateException("redis://user:secret@example/private-key");
        });
    started = query(null, false);
    worker.runNext();
    var failed = query(started.snapshotId(), false);
    assertThat(failed.status()).isEqualTo(Status.FAILED);
    assertThat(failed.message()).doesNotContain("secret", "example", "private-key");
    assertThat(failed.page()).isNull();
  }

  @Test
  void refreshIsSingleFlightAndOldConcurrentRefreshTokensReuseTheNewVersion() {
    setup(10, 10000, (pattern, accept, checkpoint) -> accept.accept(row("a", scans.get())));
    String old = query(null, false).snapshotId();
    worker.runNext();
    var refreshed = query(old, true);
    assertThat(refreshed.snapshotId()).isNotEqualTo(old);
    assertThat(query(old, true).snapshotId()).isEqualTo(refreshed.snapshotId());
    worker.runNext();
    assertThat(query(old, true).snapshotId()).isEqualTo(refreshed.snapshotId());
    assertThat(query(old, false).status()).isEqualTo(Status.EXPIRED);
    assertThat(query(refreshed.snapshotId(), false).page().getContent().getFirst().get("size"))
        .isEqualTo(2L);
    assertThat(worker.tasks).isEmpty();
  }

  @Test
  void expiryAndLruEvictionNeverSilentlyChangePinnedPagination() {
    setup(10, 10000, (pattern, accept, checkpoint) -> accept.accept(row(pattern, 1)));
    String old = query(null, false).snapshotId();
    worker.runNext();
    clock.advance(Duration.ofSeconds(60));
    assertThat(query(old, false).status()).isEqualTo(Status.EXPIRED);
    assertThat(worker.tasks).isEmpty();
    String fresh = query(null, false).snapshotId();
    worker.runNext();
    for (String pattern : List.of("a:*", "b:*")) {
      service.query(Map.of(), PageRequest.of(0, 20), pattern, null, false);
      worker.runNext();
    }
    assertThat(query(fresh, false).status()).isEqualTo(Status.EXPIRED);
    assertThat(scans).hasValue(4);
  }

  @Test
  void mutationsInvalidateInFlightWorkAndPreventStaleResultsFromReappearing() {
    setup(10, 10000, (pattern, accept, checkpoint) -> accept.accept(row("a", 1)));
    String old = query(null, false).snapshotId();
    service.invalidate();
    assertThat(query(old, false).status()).isEqualTo(Status.EXPIRED);
    assertThat(query(null, false).status()).isEqualTo(Status.BUSY);
    worker.runNext();
    assertThat(query(old, false).status()).isEqualTo(Status.EXPIRED);
    assertThat(query(null, false).status()).isEqualTo(Status.BUILDING);
  }

  @Test
  void invalidQueriesAreRejectedBeforeAnyRedisWork() {
    setup(10, 10000, (pattern, accept, checkpoint) -> accept.accept(row("a", 1)));
    for (var filters :
        List.of(
            Map.of("valuePreview", "like:secret"),
            Map.of("ttlSeconds", "equals:no"),
            Map.of("type", "equals:INVALID"),
            Map.of("$query", "{}"))) {
      assertThatThrownBy(() -> service.query(filters, PageRequest.of(0, 20), "*", null, false))
          .isInstanceOf(InvalidQueryException.class);
    }
    for (Pageable pageable :
        List.of(
            PageRequest.of(0, 101),
            Pageable.unpaged(),
            PageRequest.of(0, 20, Sort.by("valuePreview")))) {
      assertThatThrownBy(() -> service.query(Map.of(), pageable, "*", null, false))
          .isInstanceOf(InvalidQueryException.class);
    }
    assertThat(worker.tasks).isEmpty();
  }

  @Test
  void eachRequestRechecksFieldPolicyWithoutMutatingOrFilteringTheSharedSnapshot() {
    setup(10, 10000, (pattern, accept, checkpoint) -> accept.accept(row("a", 7)));
    String token = query(null, false).snapshotId();
    worker.runNext();
    var context = new AuthorizationContext();
    context.setFieldPermissions(
        Map.of("RedisEntrySummary#size", "MASKED", "RedisEntrySummary#type", "HIDDEN"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var restricted = query(token, false);
    assertThat(restricted.page().getContent().getFirst())
        .containsEntry("size", "***")
        .doesNotContainKey("type");
    assertThatThrownBy(
            () ->
                service.query(
                    Map.of(
                        "$query",
                        "{\"version\":1,\"where\":{\"field\":\"size\",\"op\":\"equals\",\"value\":7}}"),
                    PageRequest.of(0, 10),
                    "*",
                    token,
                    false))
        .isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(
            () ->
                service.query(Map.of(), PageRequest.of(0, 10, Sort.by("type")), "*", token, false))
        .isInstanceOf(InvalidQueryException.class);
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    assertThat(query(token, false).page().getContent().getFirst())
        .containsEntry("size", 7L)
        .containsEntry("type", RedisEntryType.STRING);
    assertThat(scans).hasValue(1);
  }

  @Test
  void hiddenOrMaskedIdentityDeniesEvenEmptyPagesAndSnapshotTotals() {
    setup(10, 10000, (pattern, accept, checkpoint) -> {});
    for (String access : List.of("HIDDEN", "MASKED")) {
      var context = new AuthorizationContext();
      context.setFieldPermissions(Map.of("RedisEntrySummary#key", access));
      RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
      assertThatThrownBy(() -> query(null, false)).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(RedisEntryQueryService::requireIdentity)
          .isInstanceOf(AccessDeniedException.class);
    }
    assertThat(worker.tasks).isEmpty();
  }

  @Test
  void shutdownAndTimedOutInFlightReadsDoNotEnqueueMoreWork() {
    setup(10, 10000, (pattern, accept, checkpoint) -> {});
    var started = query(null, false);
    clock.advance(Duration.ofSeconds(10));
    assertThat(query(started.snapshotId(), false).status()).isEqualTo(Status.TIMED_OUT);
    assertThat(query(started.snapshotId(), true).status()).isEqualTo(Status.TIMED_OUT);
    assertThat(worker.tasks).hasSize(1);
    service.destroy();
    assertThat(worker.isShutdown()).isTrue();
    assertThat(query(null, false).status()).isEqualTo(Status.FAILED);
  }

  @Test
  void deadlineInterruptsBlockedScansWithoutWaitingForAnotherReturnedKey() throws Exception {
    var interrupted = new java.util.concurrent.CountDownLatch(1);
    service =
        new RedisEntryQueryService(
            (pattern, accept, checkpoint) -> {
              try {
                new java.util.concurrent.CountDownLatch(1).await();
              } catch (InterruptedException error) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Redis read interrupted", error);
              }
            },
            new RedisEntryQueryService.Limits(
                10, 10000, 2, Duration.ofMillis(80), Duration.ofSeconds(60)),
            Clock.systemUTC(),
            java.util.concurrent.Executors.newSingleThreadExecutor());
    String token = query(null, false).snapshotId();
    assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(query(token, false).status()).isEqualTo(Status.TIMED_OUT);
    assertThat(query(token, false).page()).isNull();
  }

  @Test
  void concurrentCallersShareOneScanAndTheWorkerDoesNotInheritRequestAuthority() throws Exception {
    var release = new java.util.concurrent.CountDownLatch(1);
    var started = new java.util.concurrent.CountDownLatch(1);
    var workerAuthority = new java.util.concurrent.atomic.AtomicReference<Object>();
    var requestContext = new AuthorizationContext();
    requestContext.setUserId("request-only-user");
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, requestContext);
    service =
        new RedisEntryQueryService(
            (pattern, accept, checkpoint) -> {
              scans.incrementAndGet();
              workerAuthority.set(org.simplepoint.core.AuthorizationContextHolder.getContext());
              started.countDown();
              try {
                release.await();
                accept.accept(row("a", 1));
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
              }
            },
            new RedisEntryQueryService.Limits(
                10, 10000, 2, Duration.ofSeconds(10), Duration.ofSeconds(60)),
            Clock.systemUTC(),
            java.util.concurrent.Executors.newSingleThreadExecutor());
    var callers = java.util.concurrent.Executors.newFixedThreadPool(8);
    try {
      List<java.util.concurrent.Future<RedisQueryResult>> results = new java.util.ArrayList<>();
      for (int i = 0; i < 20; i++) results.add(callers.submit(() -> query(null, false)));
      java.util.Set<String> tokens = new java.util.HashSet<>();
      for (var result : results) {
        var response = result.get(2, TimeUnit.SECONDS);
        assertThat(response.status()).isEqualTo(Status.BUILDING);
        assertThat(response.page()).isNull();
        tokens.add(response.snapshotId());
      }
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(tokens).hasSize(1);
      assertThat(scans).hasValue(1);
      assertThat(workerAuthority.get()).isNull();
    } finally {
      release.countDown();
      callers.shutdownNow();
    }
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-09-19T00:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static final class ManualWorker extends AbstractExecutorService {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private boolean closed;

    void runNext() {
      tasks.removeFirst().run();
    }

    @Override
    public void shutdown() {
      closed = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      closed = true;
      tasks.clear();
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return closed;
    }

    @Override
    public boolean isTerminated() {
      return closed;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return closed;
    }

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }
  }
}
