/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api.management;

import java.net.URI;

/**
 * Blocking issue discovered during an installation dry-run.
 *
 * @param path     artifact or directory URI related to the issue
 * @param pluginId plugin id, if known
 * @param code     issue category
 * @param message  human-readable failure message
 */
public record PluginInstallPlanIssue(
    URI path,
    String pluginId,
    PluginInstallPlanIssueCode code,
    String message
) {
}
