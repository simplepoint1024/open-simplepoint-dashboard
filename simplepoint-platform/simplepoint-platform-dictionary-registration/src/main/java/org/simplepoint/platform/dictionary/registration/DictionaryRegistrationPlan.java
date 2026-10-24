package org.simplepoint.platform.dictionary.registration;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.simplepoint.platform.composition.DeploymentPlan;
import org.simplepoint.platform.composition.ModuleClientPlan;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleDictionaryPlan;

/** Pure installer input: selected metadata and fresh module clients, never host credentials. */
public final class DictionaryRegistrationPlan {
  private DictionaryRegistrationPlan() {}

  /**
   * Derives commands after validating all owners/clients, with generations read by the installer.
   */
  public static List<DictionaryInstallation> prepare(
      DeploymentPlan deployment,
      Map<String, String> clientOverrides,
      UUID operation,
      Map<String, Long> generations) {
    var declarations = ModuleDictionaryPlan.declarations(deployment);
    var clients = ModuleClientPlan.deploymentIdentities(deployment, clientOverrides, operation);
    if (!declarations.keySet().equals(generations.keySet())) {
      throw new ModuleCompositionException(
          "DICTIONARY_GENERATIONS_REQUIRED", "selected dictionary modules");
    }
    return declarations.entrySet().stream()
        .map(
            entry ->
                new DictionaryInstallation(
                    operation,
                    generations.get(entry.getKey()),
                    entry.getKey(),
                    clients.get(entry.getKey()),
                    entry.getValue()))
        .toList();
  }
}
