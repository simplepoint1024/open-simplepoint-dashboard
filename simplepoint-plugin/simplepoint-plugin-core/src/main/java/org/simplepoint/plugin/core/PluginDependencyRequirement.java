/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import org.simplepoint.plugin.api.manifest.PluginManifest;

/**
 * Normalized dependency requirement declared by a plugin manifest.
 *
 * @param id       dependency plugin id
 * @param version  optional semantic version requirement
 * @param optional whether the dependency can be absent
 */
record PluginDependencyRequirement(String id, String version, boolean optional) {

  PluginDependencyRequirement {
    id = trimToNull(id);
    version = trimToNull(version);
  }

  static PluginDependencyRequirement from(PluginManifest.PluginDependency dependency) {
    return new PluginDependencyRequirement(
        dependency.getId(),
        dependency.getVersion(),
        Boolean.TRUE.equals(dependency.getOptional()));
  }

  boolean hasVersionRequirement() {
    return version != null;
  }

  private static String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
