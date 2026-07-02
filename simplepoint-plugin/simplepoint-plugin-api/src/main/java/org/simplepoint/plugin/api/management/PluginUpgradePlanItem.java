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
import java.util.Objects;
import org.simplepoint.plugin.api.PluginArtifact;
import org.simplepoint.plugin.api.PluginStatus;

/**
 * Plugin artifact metadata in an upgrade dry-run.
 *
 * @param id           plugin manifest id
 * @param name         plugin name
 * @param version      plugin version
 * @param description  plugin description
 * @param path         plugin artifact URI
 * @param artifact     plugin artifact metadata
 * @param status       runtime status, only populated for the currently installed plugin
 * @param dependencies declared dependency resolution state
 */
public record PluginUpgradePlanItem(
    String id,
    String name,
    String version,
    String description,
    URI path,
    PluginArtifact artifact,
    PluginStatus status,
    List<PluginInstallPlanDependency> dependencies
) {

  /**
   * Creates an immutable plugin upgrade plan item.
   */
  public PluginUpgradePlanItem {
    dependencies = List.copyOf(Objects.requireNonNullElse(dependencies, List.of()));
  }
}
