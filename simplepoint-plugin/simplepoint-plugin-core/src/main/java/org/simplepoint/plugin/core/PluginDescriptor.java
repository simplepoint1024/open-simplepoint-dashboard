/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.net.URI;
import java.util.List;
import org.simplepoint.plugin.api.PluginArtifact;
import org.simplepoint.plugin.api.manifest.PluginManifest;

/**
 * Runtime descriptor produced from a plugin archive before installation.
 *
 * @param uri                  plugin archive URI
 * @param artifact             plugin artifact metadata
 * @param manifest             validated plugin manifest
 * @param dependencies         normalized dependency requirements
 * @param requiredDependencies required dependency plugin ids
 * @param optionalDependencies optional dependency plugin ids
 */
record PluginDescriptor(
    URI uri,
    PluginArtifact artifact,
    PluginManifest manifest,
    List<PluginDependencyRequirement> dependencies,
    List<String> requiredDependencies,
    List<String> optionalDependencies
) {

  PluginDescriptor {
    dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    requiredDependencies = requiredDependencies == null ? List.of() : List.copyOf(requiredDependencies);
    optionalDependencies = optionalDependencies == null ? List.of() : List.copyOf(optionalDependencies);
  }

  /**
   * Returns the plugin manifest id.
   *
   * @return plugin id
   */
  String id() {
    return manifest.getId();
  }

  /**
   * Creates a descriptor from a plugin manifest.
   *
   * @param artifact plugin artifact metadata
   * @param manifest validated plugin manifest
   * @return descriptor
   */
  static PluginDescriptor from(PluginArtifact artifact, PluginManifest manifest) {
    return new PluginDescriptor(
        artifact.uri(),
        artifact,
        manifest,
        PluginManifestRuntime.dependencies(manifest),
        PluginManifestRuntime.requiredDependencyIds(manifest),
        PluginManifestRuntime.optionalDependencyIds(manifest)
    );
  }
}
