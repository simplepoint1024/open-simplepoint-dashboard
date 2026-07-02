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

/**
 * Dry-run read model for a plugin installation request.
 *
 * @param source      requested artifact or directory URI
 * @param installable whether the request can be installed without known blockers
 * @param plugins     planned plugin artifacts
 * @param issues      blockers discovered during planning
 */
public record PluginInstallPlan(
    URI source,
    boolean installable,
    List<PluginInstallPlanItem> plugins,
    List<PluginInstallPlanIssue> issues
) {

  /**
   * Creates an immutable plugin install plan.
   */
  public PluginInstallPlan {
    plugins = List.copyOf(Objects.requireNonNullElse(plugins, List.of()));
    issues = List.copyOf(Objects.requireNonNullElse(issues, List.of()));
  }
}
