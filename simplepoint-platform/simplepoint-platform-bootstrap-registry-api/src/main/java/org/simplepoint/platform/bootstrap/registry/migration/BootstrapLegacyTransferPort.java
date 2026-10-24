package org.simplepoint.platform.bootstrap.registry.migration;

import java.util.Optional;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer.Command;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer.Identity;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer.Receipt;
import org.simplepoint.platform.bootstrap.registry.migration.BootstrapLegacyTransfer.Snapshot;

/** Trusted installation port; not a remotely exported business contract. */
public interface BootstrapLegacyTransferPort {
  /** Reads the exact historical revision without writing or treating uncertainty as success. */
  Snapshot preview(Identity identity);

  /** Commits retirement and its receipt atomically; an exact command retry returns that receipt. */
  Receipt transfer(Command command);

  /** Reads a durable owner receipt; absence does not prove a previous executor has stopped. */
  Optional<Receipt> findCompletion(Identity identity);
}
