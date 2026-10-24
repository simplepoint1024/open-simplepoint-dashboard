package org.simplepoint.installation.host;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.simplepoint.platform.installation.InstallationAction;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.platform.installation.InstallationPlan;

/** Adapts an owner's exact idempotent command and durable read-back to the installation journal. */
final class VerifiedInstallationAction implements InstallationAction {
  private final InstallationPlan.Step step;
  private final Runnable command;
  private final BooleanSupplier installed;

  VerifiedInstallationAction(String id, String fingerprint, Runnable command, BooleanSupplier installed) {
    this.step = new InstallationPlan.Step(id, id, 1, fingerprint, InstallationPlan.Retry.DURABLE_IDEMPOTENT);
    this.command = command;
    this.installed = installed;
  }

  @Override
  public InstallationPlan.Step definition() {
    return step;
  }

  @Override
  public String apply() {
    command.run();
    return completedReceipt().orElseThrow(() -> new IllegalStateException("Installation effect was not verified"));
  }

  @Override
  public Optional<String> completedReceipt() {
    return installed.getAsBoolean()
        ? Optional.of(InstallationFingerprint.of(step.kind(), step.inputFingerprint(), "verified-v1"))
        : Optional.empty();
  }
}
