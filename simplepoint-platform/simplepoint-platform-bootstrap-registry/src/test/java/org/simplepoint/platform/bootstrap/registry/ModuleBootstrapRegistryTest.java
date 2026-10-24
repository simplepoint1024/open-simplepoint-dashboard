package org.simplepoint.platform.bootstrap.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.platform.bootstrap.ModuleBootstrapScope;
import org.simplepoint.platform.bootstrap.entity.PlatformContributionRecord;

class ModuleBootstrapRegistryTest extends BootstrapRegistryTestSupport {
  private final String scope = ModuleBootstrapScope.of("example");

  @Test
  void completedModuleStateSurvivesHostChangesWithoutReapplying() {
    assertThat(required(scope)).isTrue();
    start(scope);
    registry.markApplied(scope, "domain", "data", "seed", "1", "checksum");
    assertThat(required(ModuleBootstrapScope.of("example"))).isFalse();
    assertThat(repository.findAll())
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record.getServiceName()).isEqualTo("module/example");
              assertThat(record.getStatus()).isEqualTo(PlatformContributionRecord.STATUS_APPLIED);
            });
    assertThatThrownBy(() -> start(scope)).hasMessageContaining("BOOTSTRAP_ALREADY_APPLIED");
  }

  @Test
  void historicalHostRecordsMustBeMigratedBeforeAnyNewScopeCanExecute() {
    start("common");
    registry.markApplied("common", "domain", "data", "seed", "1", "checksum");
    assertThatThrownBy(() -> required(scope))
        .hasMessageContaining("BOOTSTRAP_LEGACY_STATE_REQUIRES_MIGRATION");
    assertThatThrownBy(() -> start(scope))
        .hasMessageContaining("BOOTSTRAP_LEGACY_STATE_REQUIRES_MIGRATION");
    assertThat(repository.count()).isEqualTo(1);
    assertThat(required("common")).isFalse();
  }

  @Test
  void uncertainRunningWorkAndStaleCompletionsCannotBeReplayedOrOverwriteState() {
    start(scope);
    assertThatThrownBy(() -> required(scope)).hasMessageContaining("BOOTSTRAP_RECOVERY_REQUIRED");
    assertThatThrownBy(() -> registry.markApplied(scope, "domain", "data", "seed", "2", "checksum"))
        .hasMessageContaining("BOOTSTRAP_STATE_CONFLICT");
    assertThatThrownBy(
            () -> registry.markFailed(scope, "domain", "data", "seed", "1", "other", "failure"))
        .hasMessageContaining("BOOTSTRAP_STATE_CONFLICT");
    assertThat(repository.findAll())
        .singleElement()
        .satisfies(
            record ->
                assertThat(record.getStatus())
                    .isEqualTo(PlatformContributionRecord.STATUS_RUNNING));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void simultaneousReplicasCannotBothAcquireTheSameInitialization(boolean existing)
      throws Exception {
    if (existing) {
      start(scope);
      registry.markFailed(scope, "domain", "data", "seed", "1", "checksum", "fixture");
    }
    var readers = new CountDownLatch(2);
    var proceed = new CountDownLatch(1);
    var acquired = new AtomicInteger();
    var rejected = new AtomicInteger();
    try (var workers = Executors.newFixedThreadPool(2)) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 2; i++) {
        tasks.add(
            workers.submit(
                () -> {
                  assertThat(required(scope)).isTrue();
                  readers.countDown();
                  try {
                    if (!proceed.await(10, TimeUnit.SECONDS)) {
                      throw new AssertionError("Timed out waiting for concurrent claim");
                    }
                    start(scope);
                    acquired.incrementAndGet();
                  } catch (RuntimeException conflict) {
                    assertThat(
                            conflict
                                    instanceof
                                    org.springframework.dao.DataIntegrityViolationException
                                || conflict.getMessage().contains("BOOTSTRAP_RECOVERY_REQUIRED"))
                        .isTrue();
                    rejected.incrementAndGet();
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                  }
                }));
      }
      try {
        assertThat(readers.await(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        proceed.countDown();
      }
      for (var task : tasks) {
        task.get(15, TimeUnit.SECONDS);
      }
    }
    assertThat(acquired).hasValue(1);
    assertThat(rejected).hasValue(1);
    assertThat(repository.count()).isEqualTo(1);
    registry.markApplied(scope, "domain", "data", "seed", "1", "checksum");
    assertThat(required(scope)).isFalse();
  }

  private boolean required(String identity) {
    return registry.shouldApply(identity, "domain", "data", "seed", "1", "checksum");
  }

  private void start(String identity) {
    registry.markRunning(identity, "domain", "data", "seed", "1", "checksum");
  }
}
