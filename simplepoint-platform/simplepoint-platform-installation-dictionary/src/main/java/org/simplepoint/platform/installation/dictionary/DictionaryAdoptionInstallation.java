package org.simplepoint.platform.installation.dictionary;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransferPort;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoptionPort;
import org.simplepoint.platform.installation.InstallationAction;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.platform.installation.InstallationPlan;

/** Installer-side bridge; neither domain imports the other domain or accesses its storage. */
public final class DictionaryAdoptionInstallation {
  private final BootstrapLegacyTransferPort registry;
  private final DictionaryLegacyAdoptionPort dictionaries;

  /** Supplies local transactional domain ports; no privileged runtime RPC is introduced. */
  public DictionaryAdoptionInstallation(
      BootstrapLegacyTransferPort registry, DictionaryLegacyAdoptionPort dictionaries) {
    this.registry = Objects.requireNonNull(registry);
    this.dictionaries = Objects.requireNonNull(dictionaries);
  }

  /** Binds a previously reviewed, released declaration and previews without executing actions. */
  public Prepared prepare(
      String target,
      long generation,
      String releaseFingerprint,
      DictionaryContribution declaration,
      DictionaryLegacyAdoption.Preview data,
      BootstrapLegacyTransfer.Command transfer) {
    command(declaration, data, expectedSource(transfer));
    String sourceInput = sourceHash(expectedSource(transfer));
    var sourceStep =
        new InstallationPlan.Step(
            "registry-transfer/" + declaration.moduleCode(),
            "bootstrap-legacy-transfer",
            1,
            sourceInput,
            InstallationPlan.Retry.DURABLE_IDEMPOTENT);
    var targetStep =
        new InstallationPlan.Step(
            "dictionary-adoption/" + declaration.moduleCode(),
            "dictionary-legacy-adoption",
            1,
            InstallationFingerprint.of(
                sourceInput,
                data.declarationFingerprint(),
                data.dataFingerprint(),
                Integer.toString(data.dictionaries()),
                Integer.toString(data.items())),
            InstallationPlan.Retry.DURABLE_IDEMPOTENT);
    var plan =
        new InstallationPlan(
            transfer.operationId(),
            target,
            generation,
            releaseFingerprint,
            List.of(sourceStep, targetStep));
    InstallationAction sourceAction =
        new InstallationAction() {
          @Override
          public InstallationPlan.Step definition() {
            return sourceStep;
          }

          @Override
          public String apply() {
            return sourceHash(checkedSource(registry.transfer(transfer)));
          }

          @Override
          public Optional<String> completedReceipt() {
            return registry
                .findCompletion(transfer.expected().identity())
                .map(this::checkedSource)
                .map(DictionaryAdoptionInstallation::sourceHash);
          }

          private BootstrapLegacyTransfer.Receipt checkedSource(
              BootstrapLegacyTransfer.Receipt receipt) {
            validateSource(transfer, receipt);
            return receipt;
          }
        };
    InstallationAction targetAction =
        new InstallationAction() {
          @Override
          public InstallationPlan.Step definition() {
            return targetStep;
          }

          @Override
          public String apply() {
            var receipt =
                registry
                    .findCompletion(transfer.expected().identity())
                    .orElseThrow(() -> conflict());
            validateSource(transfer, receipt);
            return checkedTarget(dictionaries.adopt(command(declaration, data, receipt)));
          }

          @Override
          public Optional<String> completedReceipt() {
            return dictionaries.findCompletion(declaration.moduleCode()).map(this::checkedTarget);
          }

          private String checkedTarget(DictionaryLegacyAdoption.Receipt receipt) {
            if (!receipt.operationId().equals(transfer.operationId())
                || !receipt.moduleCode().equals(declaration.moduleCode())
                || !receipt.declarationFingerprint().equals(data.declarationFingerprint())
                || !receipt.sourceFingerprint().equals(transfer.expected().fingerprint())
                || !receipt.dataFingerprint().equals(data.dataFingerprint())
                || receipt.dictionaries() != data.dictionaries()
                || receipt.items() != data.items()) {
              throw conflict();
            }
            return InstallationFingerprint.of(
                receipt.operationId().toString(),
                receipt.moduleCode(),
                receipt.declarationFingerprint(),
                receipt.sourceFingerprint(),
                receipt.dataFingerprint(),
                Integer.toString(receipt.dictionaries()),
                Integer.toString(receipt.items()));
          }
        };
    return new Prepared(plan, List.of(sourceAction, targetAction));
  }

  /** Immutable executable bindings; the plan is persisted, the privileged ports are not. */
  public record Prepared(InstallationPlan plan, List<InstallationAction> actions) {
    /** Freezes the bindings supplied to the runner. */
    public Prepared {
      actions = List.copyOf(actions);
    }
  }

  private static DictionaryLegacyAdoption.Command command(
      DictionaryContribution declaration,
      DictionaryLegacyAdoption.Preview data,
      BootstrapLegacyTransfer.Receipt receipt) {
    if (!receipt.identity().type().equals("dictionary")) {
      throw conflict();
    }
    return new DictionaryLegacyAdoption.Command(
        declaration,
        data,
        new DictionaryLegacyAdoption.LegacyCompletion(
            receipt.operationId(),
            receipt.targetScope(),
            receipt.identity().moduleCode(),
            receipt.identity().key(),
            receipt.version(),
            receipt.checksum(),
            receipt.fingerprint()));
  }

  private static BootstrapLegacyTransfer.Receipt expectedSource(
      BootstrapLegacyTransfer.Command transfer) {
    var expected = transfer.expected();
    return new BootstrapLegacyTransfer.Receipt(
        transfer.operationId(),
        transfer.targetScope(),
        expected.identity(),
        expected.version(),
        expected.checksum(),
        expected.fingerprint());
  }

  private static void validateSource(
      BootstrapLegacyTransfer.Command transfer, BootstrapLegacyTransfer.Receipt receipt) {
    if (!expectedSource(transfer).equals(receipt)) {
      throw conflict();
    }
  }

  private static String sourceHash(BootstrapLegacyTransfer.Receipt receipt) {
    return InstallationFingerprint.of(
        receipt.operationId().toString(),
        receipt.targetScope(),
        receipt.identity().moduleCode(),
        receipt.identity().type(),
        receipt.identity().key(),
        receipt.version(),
        receipt.checksum(),
        receipt.fingerprint());
  }

  private static IllegalStateException conflict() {
    return new IllegalStateException("INSTALLATION_DICTIONARY_RECEIPT_CONFLICT");
  }
}
