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
 * Verifies plugin manifest version requirements against runtime versions.
 */
public final class VersionCompatibilityVerifier implements PluginCompatibilityVerifier {

  private final PluginRuntimeVersions runtimeVersions;

  /**
   * Creates a version compatibility verifier.
   *
   * @param runtimeVersions current runtime versions
   */
  public VersionCompatibilityVerifier(PluginRuntimeVersions runtimeVersions) {
    this.runtimeVersions = runtimeVersions;
  }

  @Override
  public void verify(PluginManifest manifest) {
    verifyRequirement("core", manifest.getCoreVersion(), runtimeVersions.coreVersion(), manifest.getId());
    verifyRequirement("frontend SDK", manifest.getFrontendSdkVersion(),
        runtimeVersions.frontendSdkVersion(), manifest.getId());
  }

  private void verifyRequirement(String name, String requirement, String runtimeVersion, String pluginId) {
    if (requirement == null || requirement.isBlank()) {
      return;
    }
    if (runtimeVersion == null || runtimeVersion.isBlank()) {
      throw new IllegalArgumentException(
          "Plugin " + pluginId + " requires " + name + " version " + requirement
              + ", but runtime " + name + " version is not configured");
    }
    if (!VersionRequirement.parse(requirement).matches(runtimeVersion)) {
      throw new IllegalArgumentException(
          "Plugin " + pluginId + " requires " + name + " version " + requirement
              + ", but runtime " + name + " version is " + runtimeVersion);
    }
  }
}
