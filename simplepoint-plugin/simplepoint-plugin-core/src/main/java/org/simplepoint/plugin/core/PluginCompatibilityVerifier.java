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
 * Verifies whether a plugin manifest is compatible with the current runtime.
 */
public interface PluginCompatibilityVerifier {

  /**
   * Verifies the plugin manifest.
   *
   * @param manifest validated plugin manifest
   */
  void verify(PluginManifest manifest);

  /**
   * Creates the default runtime version verifier.
   *
   * @return default compatibility verifier
   */
  static PluginCompatibilityVerifier defaults() {
    return new VersionCompatibilityVerifier(PluginRuntimeVersions.detect());
  }
}
