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
 * Dry-run read model for a plugin upgrade request.
 *
 * @param source             requested upgrade artifact URI
 * @param upgradeable        whether the request can be upgraded without known blockers
 * @param current            currently installed plugin artifact, if resolved
 * @param candidate          candidate upgrade artifact, if readable
 * @param blockingDependents installed plugins that depend on the current plugin
 * @param issues             blockers discovered during planning
 */
public record PluginUpgradePlan(
    URI source,
    boolean upgradeable,
    PluginUpgradePlanItem current,
    PluginUpgradePlanItem candidate,
    List<String> blockingDependents,
    List<PluginInstallPlanIssue> issues
) {

  /**
   * Creates an immutable plugin upgrade plan.
   */
  public PluginUpgradePlan {
    blockingDependents = List.copyOf(Objects.requireNonNullElse(blockingDependents, List.of()));
    issues = List.copyOf(Objects.requireNonNullElse(issues, List.of()));
  }
}
