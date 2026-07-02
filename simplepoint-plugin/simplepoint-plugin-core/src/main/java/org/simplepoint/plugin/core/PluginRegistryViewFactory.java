/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginStatus;
import org.simplepoint.plugin.api.management.PluginDependencyEdge;
import org.simplepoint.plugin.api.management.PluginOverview;
import org.simplepoint.plugin.api.management.PluginRegistryView;
import org.simplepoint.plugin.api.manifest.PluginManifest;

/**
 * Builds read models for the plugin registry.
 */
final class PluginRegistryViewFactory {

  PluginRegistryView create(List<Plugin> plugins, Map<String, List<String>> resolvedDependencies) {
    List<Plugin> sortedPlugins = plugins.stream()
        .sorted(Comparator.comparing(plugin -> plugin.manifest().getId()))
        .toList();
    Map<String, List<String>> dependents = dependents(resolvedDependencies);
    Map<String, Plugin> pluginsById = pluginsById(sortedPlugins);
    Map<String, List<String>> activeDependents = activeDependents(resolvedDependencies, pluginsById);
    List<PluginDependencyEdge> edges = dependencyEdges(sortedPlugins, pluginsById);
    List<PluginOverview> overviews = sortedPlugins.stream()
        .map(plugin -> overview(
            plugin,
            dependents.getOrDefault(plugin.manifest().getId(), List.of()),
            activeDependents.getOrDefault(plugin.manifest().getId(), List.of()),
            pluginsById))
        .toList();
    return new PluginRegistryView(overviews, edges);
  }

  private Map<String, Plugin> pluginsById(List<Plugin> plugins) {
    Map<String, Plugin> result = new HashMap<>();
    for (Plugin plugin : plugins) {
      result.put(plugin.manifest().getId(), plugin);
    }
    return result;
  }

  private Map<String, List<String>> dependents(Map<String, List<String>> resolvedDependencies) {
    Map<String, List<String>> dependents = new HashMap<>();
    resolvedDependencies.forEach((pluginId, dependencies) -> {
      for (String dependencyId : dependencies) {
        dependents.computeIfAbsent(dependencyId, ignored -> new ArrayList<>()).add(pluginId);
      }
    });
    dependents.values().forEach(list -> list.sort(String::compareTo));
    return dependents;
  }

  private Map<String, List<String>> activeDependents(
      Map<String, List<String>> resolvedDependencies,
      Map<String, Plugin> pluginsById
  ) {
    Map<String, List<String>> dependents = new HashMap<>();
    resolvedDependencies.forEach((pluginId, dependencies) -> {
      Plugin plugin = pluginsById.get(pluginId);
      if (plugin == null || plugin.status() == PluginStatus.DISABLED || plugin.status() == PluginStatus.FAILED) {
        return;
      }
      for (String dependencyId : dependencies) {
        dependents.computeIfAbsent(dependencyId, ignored -> new ArrayList<>()).add(pluginId);
      }
    });
    dependents.values().forEach(list -> list.sort(String::compareTo));
    return dependents;
  }

  private List<PluginDependencyEdge> dependencyEdges(List<Plugin> plugins, Map<String, Plugin> pluginsById) {
    List<PluginDependencyEdge> edges = new ArrayList<>();
    for (Plugin plugin : plugins) {
      String pluginId = plugin.manifest().getId();
      for (PluginDependencyRequirement dependency : PluginManifestRuntime.dependencies(plugin.manifest())) {
        Plugin resolved = pluginsById.get(dependency.id());
        String resolvedVersion = resolved == null ? null : resolved.manifest().getVersion();
        edges.add(new PluginDependencyEdge(
            pluginId,
            dependency.id(),
            dependency.optional(),
            resolved != null,
            dependency.version(),
            resolvedVersion,
            versionSatisfied(dependency, resolvedVersion)));
      }
    }
    edges.sort(Comparator.comparing(PluginDependencyEdge::sourcePluginId)
        .thenComparing(PluginDependencyEdge::targetPluginId));
    return edges;
  }

  private boolean versionSatisfied(PluginDependencyRequirement dependency, String resolvedVersion) {
    if (!dependency.hasVersionRequirement()) {
      return true;
    }
    if (resolvedVersion == null || resolvedVersion.isBlank()) {
      return dependency.optional();
    }
    try {
      return VersionRequirement.parse(dependency.version()).matches(resolvedVersion);
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private PluginOverview overview(
      Plugin plugin,
      List<String> dependents,
      List<String> activeDependents,
      Map<String, Plugin> pluginsById
  ) {
    PluginManifest manifest = plugin.manifest();
    boolean directlyMutable = dependents.isEmpty();
    boolean disableable = plugin.status() == PluginStatus.ENABLED && activeDependents.isEmpty();
    boolean enableable = plugin.status() == PluginStatus.DISABLED
        && requiredDependenciesEnabled(manifest, pluginsById);
    return new PluginOverview(
        manifest.getId(),
        manifest.getName(),
        manifest.getVersion(),
        manifest.getDescription(),
        manifest.getAuthor(),
        plugin.path(),
        plugin.artifact(),
        plugin.status(),
        plugin.failure(),
        PluginManifestRuntime.requiredDependencyIds(manifest),
        PluginManifestRuntime.optionalDependencyIds(manifest),
        dependents,
        directlyMutable,
        directlyMutable,
        disableable,
        enableable,
        registeredInstances(plugin)
    );
  }

  private boolean requiredDependenciesEnabled(PluginManifest manifest, Map<String, Plugin> pluginsById) {
    for (String dependencyId : PluginManifestRuntime.requiredDependencyIds(manifest)) {
      Plugin dependency = pluginsById.get(dependencyId);
      if (dependency == null || dependency.status() != PluginStatus.ENABLED) {
        return false;
      }
    }
    return true;
  }

  private Map<String, Integer> registeredInstances(Plugin plugin) {
    Map<String, Integer> result = new LinkedHashMap<>();
    plugin.registered().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> result.put(entry.getKey(), entry.getValue().size()));
    return result;
  }
}
