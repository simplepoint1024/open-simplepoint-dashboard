/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.simplepoint.plugin.storage.api.model;

/**
 * Result of an object-storage connection test.
 *
 * @param success whether the provider is reachable
 * @param message human-readable result message
 */
public record ObjectStorageConnectionTestResult(boolean success, String message) {
}
