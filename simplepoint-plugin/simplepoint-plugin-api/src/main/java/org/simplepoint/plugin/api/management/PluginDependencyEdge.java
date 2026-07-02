/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api.management;

/**
 * Dependency edge between two plugins.
 *
 * @param sourcePluginId source plugin id
 * @param targetPluginId dependency plugin id
 * @param optional       whether the dependency is optional
 * @param resolved       whether the dependency plugin is installed
 * @param versionRequirement declared version requirement, if any
 * @param resolvedVersion    resolved dependency version, if installed
 * @param versionSatisfied   whether the resolved dependency satisfies the declared version
 */
public record PluginDependencyEdge(
    String sourcePluginId,
    String targetPluginId,
    boolean optional,
    boolean resolved,
    String versionRequirement,
    String resolvedVersion,
    boolean versionSatisfied
) {
}
