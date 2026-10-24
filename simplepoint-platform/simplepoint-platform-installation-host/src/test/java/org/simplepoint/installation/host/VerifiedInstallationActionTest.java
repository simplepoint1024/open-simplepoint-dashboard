package org.simplepoint.installation.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.installation.InstallationFingerprint;

class VerifiedInstallationActionTest {
  @Test
  void reconciliationReadsTheCommittedEffectWithoutReapplying() {
    var installed = new AtomicBoolean();
    var writes = new AtomicInteger();
    var action = new VerifiedInstallationAction("module-clients", InstallationFingerprint.of("input"),
        () -> {
          writes.incrementAndGet();
          installed.set(true);
        }, installed::get);
    assertThat(action.completedReceipt()).isEmpty();
    assertThat(writes.get()).isZero();
    var receipt = action.apply();
    assertThat(action.completedReceipt()).contains(receipt);
    assertThat(writes.get()).isEqualTo(1);
    installed.set(false);
    assertThat(action.completedReceipt()).isEmpty();
  }

  @Test
  void successfulCommandWithoutCommittedEffectCannotCompleteTheStep() {
    var action = new VerifiedInstallationAction("module-clients", InstallationFingerprint.of("input"),
        () -> {}, () -> false);
    assertThatThrownBy(action::apply).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void responseLossCanBeReconciledButConflictingReadBackIsNeverSuppressed() {
    var installed = new AtomicBoolean();
    var action = new VerifiedInstallationAction("module-clients", InstallationFingerprint.of("input"),
        () -> {
          installed.set(true);
          throw new IllegalStateException("acknowledgement lost");
        }, installed::get);
    assertThatThrownBy(action::apply).isInstanceOf(IllegalStateException.class);
    assertThat(action.completedReceipt()).isPresent();
    var conflicting = new VerifiedInstallationAction("module-clients", InstallationFingerprint.of("changed"),
        () -> {}, () -> {
          throw new IllegalStateException("owner conflict");
        });
    assertThatThrownBy(conflicting::completedReceipt).isInstanceOf(IllegalStateException.class);
  }
}
