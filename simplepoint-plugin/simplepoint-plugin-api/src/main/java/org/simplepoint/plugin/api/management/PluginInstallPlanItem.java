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

/**
 * Planned plugin artifact in an installation dry-run.
 *
 * @param installOrder 1-based install order, or {@code 0} when unresolved
 * @param id           plugin manifest id
 * @param name         plugin name
 * @param version      plugin version
 * @param description  plugin description
 * @param path         plugin artifact URI
 * @param artifact     plugin artifact metadata
 * @param dependencies declared dependency resolution state
 */
public record PluginInstallPlanItem(
    int installOrder,
    String id,
    String name,
    String version,
    String description,
    URI path,
    PluginArtifact artifact,
    List<PluginInstallPlanDependency> dependencies
) {

  /**
   * Creates an immutable plugin install plan item.
   */
  public PluginInstallPlanItem {
    dependencies = List.copyOf(Objects.requireNonNullElse(dependencies, List.of()));
  }
}
