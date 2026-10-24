package org.simplepoint.platform.bootstrap.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer.Command;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer.Identity;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer.Snapshot;

class BootstrapLegacyTransfersTest extends BootstrapRegistryTestSupport {
  private final Identity identity = new Identity("domain", "dictionary", "seed");

  @Test
  void moduleTransferPreservesAllHistoryAndSkipsCompletedInitialization() {
    complete("common", "1");
    complete("older-common", "1");
    var before =
        jdbc.queryForList("select * from simpoint_platform_contribution order by service_name");
    var preview = transfers.preview(identity);
    assertThat(preview.sourceServices()).containsExactly("common", "older-common");
    var command = new Command(UUID.randomUUID(), "module/example", preview);
    var receipt = transfers.transfer(command);
    assertThat(transfers.transfer(command)).isEqualTo(receipt);
    assertThat(required("module/example", "1")).isFalse();
    assertThat(required("module/example", "2")).isTrue();
    assertThat(
            jdbc.queryForList(
                "select * from simpoint_platform_contribution where service_name not like"
                    + " 'module/%' order by service_name"))
        .isEqualTo(before);
    assertThat(repository.count()).isEqualTo(3);
    assertThatThrownBy(() -> required("common", "1")).hasMessageContaining("RETIRED_SCOPE");
    assertThatThrownBy(() -> start("common", "2")).hasMessageContaining("RETIRED_SCOPE");
    assertThatThrownBy(() -> start("unexpected-host", "1")).hasMessageContaining("RETIRED_SCOPE");
    assertThatThrownBy(() -> start("module/other", "1")).hasMessageContaining("RETIRED_SCOPE");
    assertThatThrownBy(
            () -> registry.markApplied("common", "domain", "dictionary", "seed", "1", "checksum-1"))
        .hasMessageContaining("RETIRED_SCOPE");
    assertThatThrownBy(
            () ->
                registry.markFailed(
                    "common", "domain", "dictionary", "seed", "1", "checksum-1", "failure"))
        .hasMessageContaining("RETIRED_SCOPE");
    complete("module/example", "2");
    assertThat(required("module/example", "2")).isFalse();
    assertThat(transfers.transfer(command)).isEqualTo(receipt);
  }

  @Test
  void dedicatedOwnerTransferRetiresGenericExecutionWithoutCreatingNewAppliedRow() {
    complete("common", "1");
    var command =
        new Command(UUID.randomUUID(), "owner/identity/dictionary", transfers.preview(identity));
    var receipt = transfers.transfer(command);
    assertThat(receipt.version()).isEqualTo("1");
    assertThat(receipt.checksum()).isEqualTo("checksum-1");
    assertThat(repository.count()).isEqualTo(1);
    for (String scope : new String[] {"common", "module/example", "owner/identity/dictionary"}) {
      assertThatThrownBy(() -> required(scope, "1")).hasMessageContaining("RETIRED_SCOPE");
      assertThatThrownBy(() -> start(scope, "1")).hasMessageContaining("RETIRED_SCOPE");
    }
    assertThat(transfers.transfer(command)).isEqualTo(receipt);
    assertThatThrownBy(() -> transfers.preview(identity))
        .hasMessageContaining("ALREADY_TRANSFERRED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"RUNNING", "FAILED", "conflict", "missing-time"})
  void uncertainOrConflictingHistoryRequiresExplicitRecovery(String state) {
    complete("common", "1");
    if (state.equals("conflict")) {
      complete("other", "2");
    } else if (state.equals("missing-time")) {
      jdbc.update("update simpoint_platform_contribution set applied_at=null");
    } else {
      jdbc.update("update simpoint_platform_contribution set status=?", state);
    }
    assertThatThrownBy(() -> transfers.preview(identity)).hasMessageContaining("RECOVERY_REQUIRED");
    assertThat(transferCount()).isZero();
  }

  @Test
  void changedSourceAfterPreviewCannotBeAdopted() {
    complete("common", "1");
    var preview = transfers.preview(identity);
    complete("new-host", "1");
    assertThatThrownBy(
            () -> transfers.transfer(new Command(UUID.randomUUID(), "module/example", preview)))
        .hasMessageContaining("PREVIEW_CHANGED");
    assertThat(transferCount()).isZero();
    assertThat(repository.count()).isEqualTo(2);
  }

  @Test
  void existingModularHistoryAndMissingLegacyHistoryCannotBeSilentlyReplaced() {
    assertThatThrownBy(() -> transfers.preview(identity)).hasMessageContaining("SOURCE_CONFLICT");
    complete("module/example", "1");
    assertThatThrownBy(() -> transfers.preview(identity)).hasMessageContaining("SOURCE_CONFLICT");
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void transferAndTargetCreationRollBackTogetherAndExactRetryRecovers() {
    complete("common", "1");
    var command = new Command(UUID.randomUUID(), "module/example", transfers.preview(identity));
    jdbc.execute(
        "alter table simpoint_bootstrap_legacy_transfers add constraint reject_transfer check"
            + " (target_scope <> 'module/example')");
    assertThatThrownBy(() -> transfers.transfer(command)).isInstanceOf(RuntimeException.class);
    assertThat(repository.count()).isEqualTo(1);
    assertThat(transferCount()).isZero();
    jdbc.execute("alter table simpoint_bootstrap_legacy_transfers drop constraint reject_transfer");
    transfers.transfer(command);
    assertThat(repository.count()).isEqualTo(2);
    assertThat(transferCount()).isEqualTo(1);
  }

  @Test
  void retryMustMatchOperationTargetAndExpectedRevision() {
    complete("common", "1");
    var command = new Command(UUID.randomUUID(), "module/example", transfers.preview(identity));
    transfers.transfer(command);
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new Command(UUID.randomUUID(), command.targetScope(), command.expected())))
        .hasMessageContaining("CONFLICT");
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new Command(
                        command.operationId(), "owner/identity/dictionary", command.expected())))
        .hasMessageContaining("CONFLICT");
    var changed =
        new Snapshot(
            identity,
            "2",
            command.expected().checksum(),
            command.expected().fingerprint(),
            command.expected().sourceServices());
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new Command(command.operationId(), command.targetScope(), changed)))
        .hasMessageContaining("CONFLICT");
    assertThat(transferCount()).isEqualTo(1);
  }

  @Test
  void concurrentTransfersCommitOneDurableResult() throws Exception {
    complete("common", "1");
    var command = new Command(UUID.randomUUID(), "module/example", transfers.preview(identity));
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      final var first =
          workers.submit(
              () ->
                  transaction.execute(
                      status -> {
                        var receipt = transfers.transfer(command);
                        locked.countDown();
                        await(release);
                        return receipt;
                      }));
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
      var attempted = new CountDownLatch(1);
      var second =
          workers.submit(
              () -> {
                attempted.countDown();
                return transfers.transfer(command);
              });
      assertThat(attempted.await(10, TimeUnit.SECONDS)).isTrue();
      try {
        assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
      } finally {
        release.countDown();
      }
      assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(first.get(10, TimeUnit.SECONDS));
    } finally {
      release.countDown();
    }
    assertThat(repository.count()).isEqualTo(2);
    assertThat(transferCount()).isEqualTo(1);
  }

  private long transferCount() {
    return jdbc.queryForObject(
        "select count(*) from simpoint_bootstrap_legacy_transfers", Long.class);
  }

  private boolean required(String scope, String version) {
    return registry.shouldApply(
        scope, "domain", "dictionary", "seed", version, "checksum-" + version);
  }

  private void start(String scope, String version) {
    registry.markRunning(scope, "domain", "dictionary", "seed", version, "checksum-" + version);
  }

  private void complete(String scope, String version) {
    start(scope, version);
    registry.markApplied(scope, "domain", "dictionary", "seed", version, "checksum-" + version);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for test transaction release");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
