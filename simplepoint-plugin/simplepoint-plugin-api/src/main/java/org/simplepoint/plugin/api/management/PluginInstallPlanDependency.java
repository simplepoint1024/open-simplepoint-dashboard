/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api.management;

/**
 * Dependency resolution state for a planned plugin install.
 *
 * @param id                 dependency plugin id
 * @param versionRequirement declared version requirement, if any
 * @param resolvedVersion    resolved dependency version, if present
 * @param optional           whether the dependency may be absent
 * @param present            whether the dependency is installed or part of this plan
 * @param candidate          whether the dependency is part of this plan
 */
public record PluginInstallPlanDependency(
    String id,
    String versionRequirement,
    String resolvedVersion,
    boolean optional,
    boolean present,
    boolean candidate
) {
}
