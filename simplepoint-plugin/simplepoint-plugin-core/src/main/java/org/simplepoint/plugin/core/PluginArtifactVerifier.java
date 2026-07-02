/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import org.simplepoint.plugin.api.PluginArtifact;
import org.simplepoint.plugin.api.manifest.PluginManifest;

/**
 * Verifies whether a plugin artifact is trusted for its manifest.
 */
@FunctionalInterface
public interface PluginArtifactVerifier {

  /**
   * Verifies the plugin artifact.
   *
   * @param artifact plugin artifact metadata
   * @param manifest plugin manifest read from the artifact
   */
  void verify(PluginArtifact artifact, PluginManifest manifest);
}
