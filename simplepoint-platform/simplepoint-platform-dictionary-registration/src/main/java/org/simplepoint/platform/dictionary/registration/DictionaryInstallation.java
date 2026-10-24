package org.simplepoint.platform.dictionary.registration;

import java.util.UUID;
import org.simplepoint.platform.composition.ModuleClientPlan;
import org.simplepoint.platform.dictionary.DictionaryContribution;

/**
 * Trusted local installer command; absent from the remote service contract. Null metadata retires.
 */
public record DictionaryInstallation(
    UUID operationId,
    long expectedGeneration,
    String moduleCode,
    String clientId,
    DictionaryContribution declaration) {
  /**
   * Requires an exact prior generation and fresh identity; retirement retains a durable tombstone.
   */
  public DictionaryInstallation {
    if (operationId == null
        || expectedGeneration < 0
        || expectedGeneration == Long.MAX_VALUE
        || moduleCode == null
        || moduleCode.length() > 128
        || !moduleCode.matches("[a-z][a-z0-9-]*")
        || (declaration == null
            ? clientId != null
            : !moduleCode.equals(declaration.moduleCode())
                || !ModuleClientPlan.belongsToDeployment(clientId, operationId))) {
      throw new IllegalArgumentException("Invalid dictionary installation");
    }
  }
}
