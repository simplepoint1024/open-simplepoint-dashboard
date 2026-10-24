package org.simplepoint.platform.composition;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A validated plan; planning does not construct beans, open connections or grant permissions.
 *
 * @param modules deterministic dependency-first activation order
 * @param capabilityOwners local capability/version to logical module bindings
 * @param remoteCapabilities required interfaces without a provider in this plan
 */
public record ModulePlan(
    List<ModuleDescriptor> modules, Map<String, String> capabilityOwners,
    Map<String, Class<?>> remoteCapabilities) {

  /** Freezes the plan. */
  public ModulePlan {
    modules = List.copyOf(modules);
    capabilityOwners = Collections.unmodifiableMap(new TreeMap<>(capabilityOwners));
    remoteCapabilities = Collections.unmodifiableMap(new TreeMap<>(remoteCapabilities));
  }

  /**
   * Resolves component ownership using package boundaries, never partial string prefixes.
   *
   * @param type component type
   * @return owning module when the type belongs to this plan
   */
  public Optional<ModuleDescriptor> ownerOf(final Class<?> type) {
    String name = type.getPackageName();
    return modules.stream().filter(module -> module.packages().stream()
        .anyMatch(root -> name.equals(root) || name.startsWith(root + "."))).findFirst();
  }
}
