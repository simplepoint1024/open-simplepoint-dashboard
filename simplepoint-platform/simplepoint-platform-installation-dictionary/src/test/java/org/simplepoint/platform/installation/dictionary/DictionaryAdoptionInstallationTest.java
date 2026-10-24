package org.simplepoint.platform.installation.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransferPort;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.DictionaryDeclaration;
import org.simplepoint.platform.dictionary.DictionaryFingerprint;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoption;
import org.simplepoint.platform.dictionary.registration.DictionaryLegacyAdoptionPort;

/** The actual adapter is executable with pure Java ports and no framework or domain classpath. */
class DictionaryAdoptionInstallationTest {
  private final DictionaryContribution declaration =
      new DictionaryContribution(
          "example",
          "seed",
          "1",
          100,
          List.of(new DictionaryDeclaration("example.code", "Name", null, 10, List.of())));
  private final DictionaryLegacyAdoption.Preview preview =
      new DictionaryLegacyAdoption.Preview(
          DictionaryFingerprint.of(declaration), "a".repeat(64), 1, 0);
  private final BootstrapLegacyTransfer.Command transfer =
      new BootstrapLegacyTransfer.Command(
          UUID.randomUUID(),
          "owner/identity/dictionary",
          new BootstrapLegacyTransfer.Snapshot(
              new BootstrapLegacyTransfer.Identity("example", "dictionary", "seed"),
              "1",
              "example:dictionary:seed:1",
              "b".repeat(64),
              List.of("old-host")));
  private final Registry registry = new Registry();
  private final Dictionaries dictionaries = new Dictionaries();

  @Test
  void prepareRequiresNoOwnerAccessAndLoadsNoFrameworksOrBusinessImplementations() {
    var prepared = prepare(preview);
    assertEquals(2, prepared.actions().size());
    assertEquals(0, registry.calls + dictionaries.calls);
    for (String type :
        List.of(
            "org.springframework.context.ApplicationContext",
            "jakarta.persistence.EntityManager",
            "com.rabbitmq.client.ConnectionFactory",
            "org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfers",
            "org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryLegacyAdoptions")) {
      assertThrows(ClassNotFoundException.class, () -> Class.forName(type));
    }
  }

  @Test
  void mismatchedReviewedDeclarationFailsBeforeAnyOwnerAccess() {
    var wrong =
        new DictionaryLegacyAdoption.Preview("c".repeat(64), preview.dataFingerprint(), 1, 0);
    assertThrows(IllegalArgumentException.class, () -> prepare(wrong));
    assertEquals(0, registry.calls + dictionaries.calls);
  }

  @Test
  void targetCannotExecuteBeforeActualSourceCompletion() {
    var target = prepare(preview).actions().get(1);
    assertThrows(IllegalStateException.class, target::apply);
    assertEquals(0, dictionaries.calls);
    assertEquals(1, registry.calls);
  }

  @Test
  void actualSourceIsTranslatedAndReadReceiptsAgreeWithBothAppliedResults() throws Exception {
    var actions = prepare(preview).actions();
    assertTrue(actions.getFirst().completedReceipt().isEmpty());
    String sourceHash = actions.getFirst().apply();
    assertEquals(sourceHash, actions.getFirst().completedReceipt().orElseThrow());
    String targetHash = actions.get(1).apply();
    assertEquals(targetHash, actions.get(1).completedReceipt().orElseThrow());
    assertEquals(transfer.operationId(), dictionaries.command.completion().operationId());
    assertEquals(
        registry.receipt.fingerprint(), dictionaries.command.completion().sourceFingerprint());
    assertEquals(preview, dictionaries.command.expected());
    assertEquals(declaration, dictionaries.command.declaration());
  }

  @Test
  void anotherSourceOperationCannotBeReconciledOrAdopted() {
    var original = sourceReceipt();
    registry.receipt =
        new BootstrapLegacyTransfer.Receipt(
            UUID.randomUUID(),
            original.targetScope(),
            original.identity(),
            original.version(),
            original.checksum(),
            original.fingerprint());
    var actions = prepare(preview).actions();
    assertThrows(IllegalStateException.class, actions.getFirst()::completedReceipt);
    assertThrows(IllegalStateException.class, actions.get(1)::apply);
    assertEquals(0, dictionaries.calls);
  }

  @Test
  void targetReconciliationRejectsDifferentOperationAndReviewedCounts() {
    var target = prepare(preview).actions().get(1);
    dictionaries.receipt =
        new DictionaryLegacyAdoption.Receipt(
            UUID.randomUUID(),
            "example",
            preview.declarationFingerprint(),
            transfer.expected().fingerprint(),
            preview.dataFingerprint(),
            1,
            0);
    assertThrows(IllegalStateException.class, target::completedReceipt);
    dictionaries.receipt =
        new DictionaryLegacyAdoption.Receipt(
            transfer.operationId(),
            "example",
            preview.declarationFingerprint(),
            transfer.expected().fingerprint(),
            preview.dataFingerprint(),
            1,
            1);
    assertThrows(IllegalStateException.class, target::completedReceipt);
    assertEquals(0, registry.calls);
  }

  private DictionaryAdoptionInstallation.Prepared prepare(DictionaryLegacyAdoption.Preview data) {
    return new DictionaryAdoptionInstallation(registry, dictionaries)
        .prepare("deployment", 0, "d".repeat(64), declaration, data, transfer);
  }

  private BootstrapLegacyTransfer.Receipt sourceReceipt() {
    var source = transfer.expected();
    return new BootstrapLegacyTransfer.Receipt(
        transfer.operationId(),
        transfer.targetScope(),
        source.identity(),
        source.version(),
        source.checksum(),
        source.fingerprint());
  }

  private final class Registry implements BootstrapLegacyTransferPort {
    private int calls;
    private BootstrapLegacyTransfer.Receipt receipt;

    @Override
    public BootstrapLegacyTransfer.Snapshot preview(BootstrapLegacyTransfer.Identity identity) {
      throw new AssertionError("Preparation must use previously reviewed input");
    }

    @Override
    public BootstrapLegacyTransfer.Receipt transfer(BootstrapLegacyTransfer.Command command) {
      calls++;
      assertEquals(transfer, command);
      receipt = sourceReceipt();
      return receipt;
    }

    @Override
    public Optional<BootstrapLegacyTransfer.Receipt> findCompletion(
        BootstrapLegacyTransfer.Identity identity) {
      calls++;
      assertEquals(transfer.expected().identity(), identity);
      return Optional.ofNullable(receipt);
    }
  }

  private static final class Dictionaries implements DictionaryLegacyAdoptionPort {
    private int calls;
    private DictionaryLegacyAdoption.Command command;
    private DictionaryLegacyAdoption.Receipt receipt;

    @Override
    public DictionaryLegacyAdoption.Preview preview(DictionaryContribution declaration) {
      throw new AssertionError("Preparation must use previously reviewed input");
    }

    @Override
    public DictionaryLegacyAdoption.Receipt adopt(DictionaryLegacyAdoption.Command input) {
      calls++;
      command = input;
      receipt =
          new DictionaryLegacyAdoption.Receipt(
              input.completion().operationId(),
              input.declaration().moduleCode(),
              input.expected().declarationFingerprint(),
              input.completion().sourceFingerprint(),
              input.expected().dataFingerprint(),
              input.expected().dictionaries(),
              input.expected().items());
      return receipt;
    }

    @Override
    public Optional<DictionaryLegacyAdoption.Receipt> findCompletion(String module) {
      calls++;
      assertEquals("example", module);
      return Optional.ofNullable(receipt);
    }
  }
}
