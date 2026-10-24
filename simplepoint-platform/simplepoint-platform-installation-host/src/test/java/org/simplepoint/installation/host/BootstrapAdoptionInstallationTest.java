package org.simplepoint.installation.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransferPort;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;

class BootstrapAdoptionInstallationTest {
  private final ModulePlan modules = ModuleCatalog.discover(getClass().getClassLoader())
      .plan(List.of("common-ui", "bootstrap-registry"));
  private final BootstrapLegacyTransferPort registry = mock(BootstrapLegacyTransferPort.class);

  @Test
  void preparationHasNoEffectsAndRecoveryRequiresTheExactOwnerReceipt() throws Exception {
    var command = command("module/identity");
    final var action = BootstrapAdoptionInstallation.prepare(modules, registry, command);
    verifyNoInteractions(registry);
    var source = command.expected();
    var receipt = new BootstrapLegacyTransfer.Receipt(command.operationId(), command.targetScope(),
        source.identity(), source.version(), source.checksum(), source.fingerprint());
    when(registry.transfer(command)).thenReturn(receipt);
    when(registry.findCompletion(source.identity())).thenReturn(Optional.of(receipt));
    assertEquals(action.apply(), action.completedReceipt().orElseThrow());
    when(registry.findCompletion(source.identity())).thenReturn(Optional.of(
        new BootstrapLegacyTransfer.Receipt(UUID.randomUUID(), receipt.targetScope(), receipt.identity(),
            receipt.version(), receipt.checksum(), receipt.fingerprint())));
    assertThrows(IllegalStateException.class, action::completedReceipt);
  }

  @Test
  void unselectedOrNonBootstrapTargetsAreRejectedBeforeAnyDomainCalls() {
    for (String target : List.of("module/missing", "module/common-ui", "owner/identity/dictionary")) {
      assertThrows(IllegalArgumentException.class,
          () -> BootstrapAdoptionInstallation.prepare(modules, registry, command(target)));
    }
    verifyNoInteractions(registry);
  }

  private static BootstrapLegacyTransfer.Command command(String target) {
    return new BootstrapLegacyTransfer.Command(UUID.randomUUID(), target,
        new BootstrapLegacyTransfer.Snapshot(
            new BootstrapLegacyTransfer.Identity("rbac-core", "system", "system-user"),
            "3", "rbac-core:system:system-user:3", "a".repeat(64), List.of("common")));
  }
}
