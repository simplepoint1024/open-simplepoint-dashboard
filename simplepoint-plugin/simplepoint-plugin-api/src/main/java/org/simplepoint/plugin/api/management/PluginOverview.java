/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api.management;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.api.PluginArtifact;
import org.simplepoint.plugin.api.PluginStatus;

/**
 * Read model for a managed plugin.
 *
 * @param id                   plugin id
 * @param name                 plugin name
 * @param version              plugin version
 * @param description          plugin description
 * @param author               plugin author
 * @param path                 plugin artifact URI
 * @param artifact             plugin artifact metadata
 * @param status               runtime status
 * @param failure              latest failure message
 * @param requiredDependencies required dependency plugin ids
 * @param optionalDependencies optional dependency plugin ids
 * @param dependents           installed plugins depending on this plugin
 * @param uninstallable        whether this plugin can be uninstalled directly
 * @param upgradeable          whether this plugin can be upgraded directly
 * @param disableable          whether this plugin can be disabled directly
 * @param enableable           whether this plugin can be enabled directly
 * @param registeredInstances  registered instance count by group
 */
public record PluginOverview(
    String id,
    String name,
    String version,
    String description,
    String author,
    URI path,
    PluginArtifact artifact,
    PluginStatus status,
    String failure,
    List<String> requiredDependencies,
    List<String> optionalDependencies,
    List<String> dependents,
    boolean uninstallable,
    boolean upgradeable,
    boolean disableable,
    boolean enableable,
    Map<String, Integer> registeredInstances
) {

  /**
   * Creates an immutable plugin overview.
   */
  public PluginOverview {
    requiredDependencies = List.copyOf(requiredDependencies);
    optionalDependencies = List.copyOf(optionalDependencies);
    dependents = List.copyOf(dependents);
    registeredInstances = Map.copyOf(registeredInstances);
  }
}
