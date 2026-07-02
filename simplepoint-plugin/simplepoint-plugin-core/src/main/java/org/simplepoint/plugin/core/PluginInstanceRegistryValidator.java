/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.simplepoint.plugin.api.Plugin;

/**
 * Validates plugin instance registrations before they are submitted to handlers.
 */
final class PluginInstanceRegistryValidator {

  void validate(String pluginId, Map<String, Set<Plugin.PluginInstance>> instances) {
    if (instances == null || instances.isEmpty()) {
      return;
    }
    Set<String> seen = new HashSet<>();
    Set<String> duplicates = new TreeSet<>();
    instances.values().forEach(groupInstances -> {
      if (groupInstances == null) {
        return;
      }
      for (Plugin.PluginInstance instance : groupInstances) {
        String name = instance == null ? null : trimToNull(instance.getName());
        if (name == null) {
          continue;
        }
        if (!seen.add(name)) {
          duplicates.add(name);
        }
      }
    });
    if (!duplicates.isEmpty()) {
      throw new IllegalStateException("Duplicate plugin instance names for "
          + pluginId + ": " + String.join(", ", duplicates));
    }
  }

  private String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
