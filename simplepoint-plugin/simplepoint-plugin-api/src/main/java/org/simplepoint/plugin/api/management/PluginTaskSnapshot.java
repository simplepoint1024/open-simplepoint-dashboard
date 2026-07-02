/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.api.management;

import java.time.Instant;

/**
 * Read model for a plugin runtime registration task.
 *
 * @param id        task id
 * @param pluginId  affected plugin id
 * @param operation operation represented by the task
 * @param status    task status
 * @param attempts  execution attempts
 * @param createdAt task creation time
 * @param updatedAt latest task update time
 * @param failure   latest failure message, if any
 */
public record PluginTaskSnapshot(
    String id,
    String pluginId,
    PluginOperation operation,
    PluginTaskStatus status,
    int attempts,
    Instant createdAt,
    Instant updatedAt,
    String failure
) {
}
