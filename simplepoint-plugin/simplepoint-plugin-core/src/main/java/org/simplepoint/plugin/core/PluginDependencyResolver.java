/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orders plugin descriptors by manifest dependencies.
 */
final class PluginDependencyResolver {

  /**
   * Sorts plugins so dependencies are installed before dependents.
   *
   * @param descriptors        plugin descriptors to sort
   * @param installedPluginIds plugin ids that are already installed
   * @return sorted descriptors
   */
  List<PluginDescriptor> sort(Collection<PluginDescriptor> descriptors, Collection<String> installedPluginIds) {
    Map<String, String> installedVersions = new LinkedHashMap<>();
    if (installedPluginIds != null) {
      for (String pluginId : installedPluginIds) {
        installedVersions.put(pluginId, null);
      }
    }
    return sort(descriptors, installedVersions);
  }

  /**
   * Sorts plugins so dependencies are installed before dependents.
   *
   * @param descriptors             plugin descriptors to sort
   * @param installedPluginVersions plugin id to installed version map
   * @return sorted descriptors
   */
  List<PluginDescriptor> sort(
      Collection<PluginDescriptor> descriptors,
      Map<String, String> installedPluginVersions
  ) {
    Map<String, String> installedVersions = copyInstalledVersions(installedPluginVersions);
    Set<String> installed = installedVersions.keySet();
    Map<String, PluginDescriptor> candidates = new LinkedHashMap<>();
    for (PluginDescriptor descriptor : descriptors) {
      PluginDescriptor previous = candidates.putIfAbsent(descriptor.id(), descriptor);
      if (previous != null) {
        throw new IllegalStateException("Duplicate plugin id in install directory: " + descriptor.id());
      }
      if (installed.contains(descriptor.id())) {
        throw new IllegalStateException("Plugin " + descriptor.id() + " is already installed");
      }
    }

    Map<String, Set<String>> graph = buildGraph(candidates, installedVersions);
    List<PluginDescriptor> sorted = new ArrayList<>();
    Map<String, VisitState> states = new HashMap<>();
    for (String pluginId : candidates.keySet()) {
      visit(pluginId, graph, candidates, states, sorted, new LinkedHashSet<>());
    }
    return sorted;
  }

  /**
   * Verifies that a single plugin descriptor can resolve its installed dependencies.
   *
   * @param descriptor              descriptor to verify
   * @param installedPluginVersions plugin id to installed version map
   */
  void verifyInstalledDependencies(
      PluginDescriptor descriptor,
      Map<String, String> installedPluginVersions
  ) {
    Map<String, String> installedVersions = copyInstalledVersions(installedPluginVersions);
    resolveDependencies(descriptor, Map.of(), installedVersions, installedVersions);
  }

  private Map<String, Set<String>> buildGraph(
      Map<String, PluginDescriptor> candidates,
      Map<String, String> installedVersions
  ) {
    Map<String, Set<String>> graph = new LinkedHashMap<>();
    Map<String, String> availableVersions = new LinkedHashMap<>(installedVersions);
    candidates.forEach((pluginId, descriptor) -> availableVersions.put(pluginId, descriptor.manifest().getVersion()));
    for (PluginDescriptor descriptor : candidates.values()) {
      graph.put(descriptor.id(), resolveDependencies(descriptor, candidates, installedVersions, availableVersions));
    }
    return graph;
  }

  private Set<String> resolveDependencies(
      PluginDescriptor descriptor,
      Map<String, PluginDescriptor> candidates,
      Map<String, String> installedVersions,
      Map<String, String> availableVersions
  ) {
    Set<String> dependencies = new LinkedHashSet<>();
    for (PluginDependencyRequirement dependency : descriptor.dependencies()) {
      String dependencyId = dependency.id();
      boolean candidateExists = candidates.containsKey(dependencyId);
      boolean installedExists = installedVersions.containsKey(dependencyId);
      if (!dependency.optional() && !candidateExists && !installedExists) {
        throw new IllegalStateException(
            "Plugin " + descriptor.id() + " requires missing dependency " + dependencyId);
      }
      if (candidateExists || installedExists) {
        verifyVersion(descriptor.id(), dependency, availableVersions.get(dependencyId));
      }
      if (candidateExists) {
        dependencies.add(dependencyId);
      }
    }
    return dependencies;
  }

  private void verifyVersion(
      String pluginId,
      PluginDependencyRequirement dependency,
      String actualVersion
  ) {
    if (!dependency.hasVersionRequirement()) {
      return;
    }
    if (actualVersion == null || actualVersion.isBlank()) {
      throw new IllegalStateException(
          "Plugin " + pluginId + " requires dependency " + dependency.id() + " version "
              + dependency.version() + ", but resolved version is unknown");
    }
    if (!VersionRequirement.parse(dependency.version()).matches(actualVersion)) {
      throw new IllegalStateException(
          "Plugin " + pluginId + " requires dependency " + dependency.id() + " version "
              + dependency.version() + ", but resolved version is " + actualVersion);
    }
  }

  private Map<String, String> copyInstalledVersions(Map<String, String> installedPluginVersions) {
    Map<String, String> installedVersions = new LinkedHashMap<>();
    if (installedPluginVersions == null || installedPluginVersions.isEmpty()) {
      return installedVersions;
    }
    installedPluginVersions.forEach((pluginId, version) -> {
      if (pluginId != null && !pluginId.isBlank()) {
        installedVersions.put(pluginId, version);
      }
    });
    return installedVersions;
  }

  private void visit(
      String pluginId,
      Map<String, Set<String>> graph,
      Map<String, PluginDescriptor> candidates,
      Map<String, VisitState> states,
      List<PluginDescriptor> sorted,
      LinkedHashSet<String> path
  ) {
    VisitState state = states.get(pluginId);
    if (state == VisitState.VISITED) {
      return;
    }
    if (state == VisitState.VISITING) {
      path.add(pluginId);
      throw new IllegalStateException("Circular plugin dependency detected: " + String.join(" -> ", path));
    }

    states.put(pluginId, VisitState.VISITING);
    path.add(pluginId);
    for (String dependencyId : graph.getOrDefault(pluginId, Set.of())) {
      visit(dependencyId, graph, candidates, states, sorted, path);
    }
    path.remove(pluginId);
    states.put(pluginId, VisitState.VISITED);
    sorted.add(candidates.get(pluginId));
  }

  private enum VisitState {
    VISITING,
    VISITED
  }
}
