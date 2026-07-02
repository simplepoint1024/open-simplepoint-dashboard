/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.api.manifest.PluginManifest;

/**
 * Verifies that backend manifest groups have registered runtime handlers.
 */
final class PluginBackendGroupVerifier {

  void verify(PluginManifest manifest, Set<String> supportedGroups) {
    Set<String> requestedGroups = requestedGroups(manifest);
    if (requestedGroups.isEmpty()) {
      return;
    }
    Set<String> unsupportedGroups = new LinkedHashSet<>(requestedGroups);
    unsupportedGroups.removeAll(supportedGroups == null ? Set.of() : supportedGroups);
    if (!unsupportedGroups.isEmpty()) {
      throw new IllegalStateException("Unsupported plugin backend groups: " + String.join(", ", unsupportedGroups));
    }
  }

  private Set<String> requestedGroups(PluginManifest manifest) {
    Set<String> groups = new LinkedHashSet<>();
    PluginManifest.Backend backend = manifest.getBackend();
    if (backend == null) {
      return groups;
    }
    addPackageScanGroups(groups, backend.getPackageScan());
    addInstanceGroups(groups, backend.getInstances());
    return groups;
  }

  private void addPackageScanGroups(Set<String> groups, Map<String, String> packageScan) {
    if (packageScan == null || packageScan.isEmpty()) {
      return;
    }
    packageScan.keySet().stream()
        .filter(PluginBackendGroupVerifier::hasText)
        .forEach(groups::add);
  }

  private void addInstanceGroups(
      Set<String> groups,
      Map<String, Set<PluginManifest.PluginInstanceContribution>> instances
  ) {
    if (instances == null || instances.isEmpty()) {
      return;
    }
    instances.forEach((group, items) -> {
      if (items == null || items.isEmpty()) {
        if (hasText(group)) {
          groups.add(group);
        }
        return;
      }
      for (PluginManifest.PluginInstanceContribution item : items) {
        String effectiveGroup = item == null ? group : firstNonBlank(item.getGroup(), group);
        if (hasText(effectiveGroup)) {
          groups.add(effectiveGroup);
        }
      }
    });
  }

  private String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (hasText(value)) {
        return value;
      }
    }
    return null;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
