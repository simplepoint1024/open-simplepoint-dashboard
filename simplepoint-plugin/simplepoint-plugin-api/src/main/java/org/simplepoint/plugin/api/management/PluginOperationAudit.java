/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api.management;

import java.net.URI;
import java.time.Instant;
import org.simplepoint.plugin.api.PluginArtifact;

/**
 * Audit entry for a plugin management operation.
 *
 * @param id             audit entry id
 * @param operation      operation type
 * @param outcome        operation outcome
 * @param pluginId       plugin manifest id, if known
 * @param pluginVersion  plugin manifest version, if known
 * @param path           plugin artifact URI, if known
 * @param artifact       plugin artifact metadata, if known
 * @param startedAt      operation start time
 * @param completedAt    operation completion time
 * @param durationMillis operation duration in milliseconds
 * @param failure        failure message, if the operation failed
 */
public record PluginOperationAudit(
    String id,
    PluginOperation operation,
    PluginOperationOutcome outcome,
    String pluginId,
    String pluginVersion,
    URI path,
    PluginArtifact artifact,
    Instant startedAt,
    Instant completedAt,
    long durationMillis,
    String failure
) {
}
