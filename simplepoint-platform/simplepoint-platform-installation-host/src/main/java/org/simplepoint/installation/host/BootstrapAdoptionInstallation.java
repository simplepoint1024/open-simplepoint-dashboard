package org.simplepoint.installation.host;

import java.util.Optional;
import org.simplepoint.platform.bootstrap.PlatformBootstrapModuleInitializer;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransferPort;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.installation.InstallationAction;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.platform.installation.InstallationPlan;

/** Binds reviewed historical bootstrap ownership to a selected module through its owning port. */
final class BootstrapAdoptionInstallation {
  private BootstrapAdoptionInstallation() {}

  static InstallationAction prepare(ModulePlan modules, BootstrapLegacyTransferPort registry,
      BootstrapLegacyTransfer.Command command) {
    String target = command.targetScope();
    if (!target.startsWith("module/") || modules.modules().stream().noneMatch(module ->
        module.id().equals(target.substring("module/".length()))
            && module.initializers().contains(PlatformBootstrapModuleInitializer.class))) {
      throw new IllegalArgumentException("Legacy bootstrap target must declare module initialization");
    }
    var expected = command.expected();
    var identity = expected.identity();
    var receipt = new BootstrapLegacyTransfer.Receipt(command.operationId(), target, identity,
        expected.version(), expected.checksum(), expected.fingerprint());
    String fingerprint = InstallationFingerprint.of(command.operationId().toString(), target,
        identity.moduleCode(), identity.type(), identity.key(), expected.version(),
        expected.checksum(), expected.fingerprint());
    var step = new InstallationPlan.Step("bootstrap-transfer/" + InstallationFingerprint.of(
        identity.moduleCode(), identity.type(), identity.key()), "bootstrap-legacy-transfer", 1, fingerprint,
        InstallationPlan.Retry.DURABLE_IDEMPOTENT);
    return new InstallationAction() {
      @Override
      public InstallationPlan.Step definition() {
        return step;
      }

      @Override
      public String apply() {
        return verify(registry.transfer(command));
      }

      @Override
      public Optional<String> completedReceipt() {
        return registry.findCompletion(identity).map(this::verify);
      }

      private String verify(BootstrapLegacyTransfer.Receipt actual) {
        if (!receipt.equals(actual)) {
          throw new IllegalStateException("INSTALLATION_BOOTSTRAP_RECEIPT_CONFLICT");
        }
        return fingerprint;
      }
    };
  }
}
