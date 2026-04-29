/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.api.pojo.dto;

/**
 * DTO for setting a user preference value.
 *
 * @param value The preference value to store (typically a JSON string).
 */
public record UserPreferenceDto(String value) {

}
