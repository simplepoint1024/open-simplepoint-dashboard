/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.api.manifest.PluginManifest;

/**
 * Lightweight validation for plugin manifests.
 */
final class PluginManifestValidator {

  private static final List<String> RUNTIME_MODES = List.of("hot", "restart", "external");

  private PluginManifestValidator() {
  }

  static void validate(PluginManifest manifest) {
    if (manifest == null) {
      throw new IllegalArgumentException("Plugin manifest is empty");
    }
    requireText(manifest.getId(), "Plugin manifest id is required");
    requireText(manifest.getName(), "Plugin manifest name is required");
    requireText(manifest.getVersion(), "Plugin manifest version is required");
    validateVersionRequirement(manifest.getCoreVersion(), "coreVersion for plugin " + manifest.getId());
    validateVersionRequirement(
        manifest.getFrontendSdkVersion(),
        "frontendSdkVersion for plugin " + manifest.getId());

    if (manifest.getDependencies() != null) {
      Set<String> dependencyIds = new HashSet<>();
      for (PluginManifest.PluginDependency dependency : manifest.getDependencies()) {
        if (dependency == null) {
          throw new IllegalArgumentException("Plugin dependency declaration is required for plugin " + manifest.getId());
        }
        requireText(dependency.getId(), "Dependency id is required for plugin " + manifest.getId());
        String dependencyId = dependency.getId().trim();
        if (!dependencyIds.add(dependencyId)) {
          throw new IllegalArgumentException(
              "Duplicate dependency " + dependencyId + " for plugin " + manifest.getId());
        }
        validateVersionRequirement(
            dependency.getVersion(),
            "dependency " + dependencyId + " for plugin " + manifest.getId());
      }
    }

    if (manifest.getBackend() != null && manifest.getBackend().getServices() != null) {
      for (PluginManifest.ServiceArtifact service : manifest.getBackend().getServices()) {
        requireText(service.getName(), "Backend service name is required");
        String runtimeMode = service.getRuntimeMode();
        if (runtimeMode != null && !runtimeMode.isBlank() && !RUNTIME_MODES.contains(runtimeMode)) {
          throw new IllegalArgumentException(
              "Unsupported backend runtimeMode '" + runtimeMode + "' for service " + service.getName());
        }
      }
    }

    if (manifest.getFrontend() != null && manifest.getFrontend().getRemotes() != null) {
      for (PluginManifest.RemoteContribution remote : manifest.getFrontend().getRemotes()) {
        requireText(remote.getName(), "Frontend remote name is required");
        requireText(remote.getEntry(), "Frontend remote entry is required");
      }
    }

    if (manifest.getResources() != null) {
      validateResources(manifest.getResources(), new HashSet<>(), new HashSet<>());
    }
  }

  private static void validateResources(
      List<PluginManifest.ResourceContribution> resources,
      Set<String> codes,
      Set<String> paths
  ) {
    for (PluginManifest.ResourceContribution resource : resources) {
      requireText(resource.getCode(), "Resource code is required");
      requireText(resource.getName(), "Resource name is required for " + resource.getCode());
      if (!codes.add(resource.getCode())) {
        throw new IllegalArgumentException("Duplicate resource code " + resource.getCode());
      }
      if (resource.getPath() != null && !resource.getPath().isBlank() && !paths.add(resource.getPath())) {
        throw new IllegalArgumentException("Duplicate resource path " + resource.getPath());
      }
      if (resource.getChildren() != null) {
        validateResources(resource.getChildren(), codes, paths);
      }
    }
  }

  private static void requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
  }

  private static void validateVersionRequirement(String requirement, String target) {
    if (requirement == null || requirement.isBlank()) {
      return;
    }
    try {
      VersionRequirement.parse(requirement);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(
          "Invalid version requirement '" + requirement + "' for " + target,
          exception);
    }
  }
}
