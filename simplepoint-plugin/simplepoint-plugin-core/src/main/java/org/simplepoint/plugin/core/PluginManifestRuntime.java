/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.manifest.PluginManifest;

/**
 * Extracts runtime registration data from a plugin manifest.
 */
final class PluginManifestRuntime {

  private PluginManifestRuntime() {
  }

  static String pluginId(PluginManifest manifest) {
    return manifest.getId();
  }

  static List<String> dependencyIds(PluginManifest manifest) {
    return dependencies(manifest).stream()
        .map(PluginDependencyRequirement::id)
        .toList();
  }

  static List<String> requiredDependencyIds(PluginManifest manifest) {
    return dependencies(manifest).stream()
        .filter(dependency -> !dependency.optional())
        .map(PluginDependencyRequirement::id)
        .toList();
  }

  static List<String> optionalDependencyIds(PluginManifest manifest) {
    return dependencies(manifest).stream()
        .filter(PluginDependencyRequirement::optional)
        .map(PluginDependencyRequirement::id)
        .toList();
  }

  static List<PluginDependencyRequirement> dependencies(PluginManifest manifest) {
    if (manifest.getDependencies() == null || manifest.getDependencies().isEmpty()) {
      return List.of();
    }
    return manifest.getDependencies().stream()
        .map(PluginDependencyRequirement::from)
        .filter(dependency -> hasText(dependency.id()))
        .toList();
  }

  static Map<String, String> packageScan(PluginManifest manifest) {
    PluginManifest.Backend backend = manifest.getBackend();
    if (backend == null || backend.getPackageScan() == null) {
      return Map.of();
    }
    return backend.getPackageScan();
  }

  static Map<String, Set<Plugin.PluginInstance>> instances(PluginManifest manifest) {
    PluginManifest.Backend backend = manifest.getBackend();
    if (backend == null || backend.getInstances() == null || backend.getInstances().isEmpty()) {
      return Map.of();
    }

    Map<String, Set<Plugin.PluginInstance>> result = new HashMap<>();
    backend.getInstances().forEach((declaredGroup, items) -> {
      if (items == null || items.isEmpty()) {
        return;
      }
      for (PluginManifest.PluginInstanceContribution item : items) {
        String beanClassName = firstNonBlank(item.getClassName(), item.getName());
        String beanName = firstNonBlank(item.getName(), beanClassName);
        String beanGroup = firstNonBlank(item.getGroup(), declaredGroup);
        if (hasText(beanName) && hasText(beanClassName) && hasText(beanGroup)) {
          result.computeIfAbsent(beanGroup, ignored -> new HashSet<>())
              .add(new Plugin.PluginInstance(beanName, beanClassName, beanGroup));
        }
      }
    });
    return result;
  }

  private static String firstNonBlank(String... values) {
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
