/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.coordination;

import org.simplepoint.plugin.api.PluginOperationEvent;

/**
 * Stored plugin operation event with JDBC cursor metadata.
 *
 * @param id       stored event id
 * @param originId node that recorded the event
 * @param event    plugin operation event
 */
record JdbcPluginStoredOperationEvent(String id, String originId, PluginOperationEvent event) {
}
