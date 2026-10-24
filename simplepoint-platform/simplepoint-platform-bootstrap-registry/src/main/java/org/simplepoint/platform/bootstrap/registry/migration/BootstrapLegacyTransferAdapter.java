package org.simplepoint.platform.bootstrap.registry.migration;

import java.util.Optional;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer.Command;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer.Identity;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer.Receipt;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer.Snapshot;
import org.springframework.stereotype.Component;

/**
 * Binds the pure local port to the existing proxied owner; never starts an installer transaction.
 */
@Component
public final class BootstrapLegacyTransferAdapter implements BootstrapLegacyTransferPort {
  private final BootstrapLegacyTransfers owner;

  /** Injection preserves the owner's existing transaction proxy and runtime scope checks. */
  public BootstrapLegacyTransferAdapter(BootstrapLegacyTransfers owner) {
    this.owner = owner;
  }

  @Override
  public Snapshot preview(Identity identity) {
    return owner.preview(identity);
  }

  @Override
  public Receipt transfer(Command command) {
    return owner.transfer(command);
  }

  @Override
  public Optional<Receipt> findCompletion(Identity identity) {
    return owner.findCompletion(identity);
  }
}
