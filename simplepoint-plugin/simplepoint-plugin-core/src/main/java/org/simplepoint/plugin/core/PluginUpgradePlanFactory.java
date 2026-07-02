/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.management.PluginInstallPlanDependency;
import org.simplepoint.plugin.api.management.PluginInstallPlanIssue;
import org.simplepoint.plugin.api.management.PluginInstallPlanIssueCode;
import org.simplepoint.plugin.api.management.PluginUpgradePlan;
import org.simplepoint.plugin.api.management.PluginUpgradePlanItem;
import org.simplepoint.plugin.api.manifest.PluginManifest;

/**
 * Builds dry-run read models for plugin upgrade requests.
 */
final class PluginUpgradePlanFactory {

  PluginUpgradePlan create(
      URI source,
      Plugin current,
      PluginDescriptor candidate,
      Map<String, String> installedPluginVersions,
      List<String> blockingDependents,
      List<PluginInstallPlanIssue> issues
  ) {
    Map<String, String> installedVersions = copyVersions(installedPluginVersions);
    String candidateId = candidate == null ? null : candidate.id();
    Map<String, String> candidateAvailableVersions = new LinkedHashMap<>(installedVersions);
    if (candidateId != null) {
      candidateAvailableVersions.remove(candidateId);
    }
    boolean upgradeable = issues == null || issues.isEmpty();
    return new PluginUpgradePlan(
        source,
        upgradeable,
        current == null ? null : item(current, installedVersions),
        candidate == null ? null : item(candidate, candidateAvailableVersions),
        blockingDependents,
        issues);
  }

  PluginUpgradePlan failed(URI source, PluginInstallPlanIssueCode code, Exception exception) {
    return new PluginUpgradePlan(
        source,
        false,
        null,
        null,
        List.of(),
        List.of(issue(source, null, code, exception)));
  }

  PluginInstallPlanIssue issue(
      URI path,
      String pluginId,
      PluginInstallPlanIssueCode code,
      Exception exception
  ) {
    return new PluginInstallPlanIssue(path, pluginId, code, failureMessage(exception));
  }

  private PluginUpgradePlanItem item(Plugin plugin, Map<String, String> installedVersions) {
    PluginManifest manifest = plugin.manifest();
    return new PluginUpgradePlanItem(
        manifest.getId(),
        manifest.getName(),
        manifest.getVersion(),
        manifest.getDescription(),
        plugin.path(),
        plugin.artifact(),
        plugin.status(),
        dependencies(PluginManifestRuntime.dependencies(manifest), installedVersions));
  }

  private PluginUpgradePlanItem item(PluginDescriptor descriptor, Map<String, String> availableVersions) {
    PluginManifest manifest = descriptor.manifest();
    return new PluginUpgradePlanItem(
        manifest.getId(),
        manifest.getName(),
        manifest.getVersion(),
        manifest.getDescription(),
        descriptor.uri(),
        descriptor.artifact(),
        null,
        dependencies(descriptor.dependencies(), availableVersions));
  }

  private List<PluginInstallPlanDependency> dependencies(
      List<PluginDependencyRequirement> requirements,
      Map<String, String> availableVersions
  ) {
    List<PluginInstallPlanDependency> dependencies = new ArrayList<>();
    for (PluginDependencyRequirement dependency : requirements) {
      boolean present = availableVersions.containsKey(dependency.id());
      dependencies.add(new PluginInstallPlanDependency(
          dependency.id(),
          dependency.version(),
          availableVersions.get(dependency.id()),
          dependency.optional(),
          present,
          false));
    }
    return dependencies;
  }

  private Map<String, String> copyVersions(Map<String, String> versions) {
    Map<String, String> copy = new LinkedHashMap<>();
    if (versions == null || versions.isEmpty()) {
      return copy;
    }
    versions.forEach((pluginId, version) -> {
      if (pluginId != null && !pluginId.isBlank()) {
        copy.put(pluginId, version);
      }
    });
    return copy;
  }

  private String failureMessage(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return exception.getClass().getSimpleName();
    }
    return message;
  }
}
