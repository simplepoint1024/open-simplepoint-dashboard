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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.api.management.PluginInstallPlan;
import org.simplepoint.plugin.api.management.PluginInstallPlanDependency;
import org.simplepoint.plugin.api.management.PluginInstallPlanIssue;
import org.simplepoint.plugin.api.management.PluginInstallPlanIssueCode;
import org.simplepoint.plugin.api.management.PluginInstallPlanItem;
import org.simplepoint.plugin.api.manifest.PluginManifest;

/**
 * Builds dry-run read models for plugin installation requests.
 */
final class PluginInstallPlanFactory {

  private final PluginDependencyResolver dependencyResolver = new PluginDependencyResolver();

  PluginInstallPlan create(
      URI source,
      Collection<PluginDescriptor> descriptors,
      Map<String, String> installedPluginVersions,
      List<PluginInstallPlanIssue> initialIssues
  ) {
    List<PluginDescriptor> descriptorList = List.copyOf(descriptors);
    Map<String, String> installedVersions = copyVersions(installedPluginVersions);
    List<PluginDescriptor> orderedDescriptors = descriptorList;
    List<PluginInstallPlanIssue> issues = new ArrayList<>(initialIssues);
    if (!descriptorList.isEmpty()) {
      try {
        orderedDescriptors = dependencyResolver.sort(descriptorList, installedVersions);
      } catch (RuntimeException exception) {
        issues.add(issue(source, null, PluginInstallPlanIssueCode.DEPENDENCY_RESOLUTION_FAILED, exception));
      }
    }
    boolean installable = issues.isEmpty();
    return new PluginInstallPlan(
        source,
        installable,
        items(orderedDescriptors, installedVersions, installable),
        issues);
  }

  PluginInstallPlan failed(URI source, PluginInstallPlanIssueCode code, Exception exception) {
    return new PluginInstallPlan(
        source,
        false,
        List.of(),
        List.of(issue(source, null, code, exception)));
  }

  PluginInstallPlan empty(URI source) {
    return new PluginInstallPlan(source, true, List.of(), List.of());
  }

  PluginInstallPlanIssue issue(
      URI path,
      String pluginId,
      PluginInstallPlanIssueCode code,
      Exception exception
  ) {
    return new PluginInstallPlanIssue(path, pluginId, code, failureMessage(exception));
  }

  private List<PluginInstallPlanItem> items(
      List<PluginDescriptor> descriptors,
      Map<String, String> installedVersions,
      boolean ordered
  ) {
    Map<String, String> candidateVersions = candidateVersions(descriptors);
    Map<String, String> availableVersions = new LinkedHashMap<>(installedVersions);
    availableVersions.putAll(candidateVersions);
    List<PluginInstallPlanItem> items = new ArrayList<>();
    for (int i = 0; i < descriptors.size(); i++) {
      items.add(item(descriptors.get(i), i + 1, ordered, candidateVersions, availableVersions));
    }
    return items.stream()
        .sorted(Comparator.comparingInt(PluginInstallPlanItem::installOrder)
            .thenComparing(PluginInstallPlanItem::id))
        .toList();
  }

  private PluginInstallPlanItem item(
      PluginDescriptor descriptor,
      int order,
      boolean ordered,
      Map<String, String> candidateVersions,
      Map<String, String> availableVersions
  ) {
    PluginManifest manifest = descriptor.manifest();
    return new PluginInstallPlanItem(
        ordered ? order : 0,
        manifest.getId(),
        manifest.getName(),
        manifest.getVersion(),
        manifest.getDescription(),
        descriptor.uri(),
        descriptor.artifact(),
        dependencies(descriptor, candidateVersions, availableVersions));
  }

  private List<PluginInstallPlanDependency> dependencies(
      PluginDescriptor descriptor,
      Map<String, String> candidateVersions,
      Map<String, String> availableVersions
  ) {
    List<PluginInstallPlanDependency> dependencies = new ArrayList<>();
    for (PluginDependencyRequirement dependency : descriptor.dependencies()) {
      boolean candidate = candidateVersions.containsKey(dependency.id());
      boolean present = availableVersions.containsKey(dependency.id());
      dependencies.add(new PluginInstallPlanDependency(
          dependency.id(),
          dependency.version(),
          availableVersions.get(dependency.id()),
          dependency.optional(),
          present,
          candidate));
    }
    return dependencies;
  }

  private Map<String, String> candidateVersions(List<PluginDescriptor> descriptors) {
    Map<String, String> versions = new LinkedHashMap<>();
    for (PluginDescriptor descriptor : descriptors) {
      versions.putIfAbsent(descriptor.id(), descriptor.manifest().getVersion());
    }
    return versions;
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
