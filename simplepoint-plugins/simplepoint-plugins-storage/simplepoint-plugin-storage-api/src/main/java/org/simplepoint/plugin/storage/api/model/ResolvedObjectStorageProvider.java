/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.simplepoint.plugin.storage.api.model;

import org.simplepoint.plugin.storage.api.properties.ObjectStorageProperties;

/**
 * Internal runtime provider containing decrypted connection properties.
 *
 * @param code provider code
 * @param properties runtime provider properties
 */
public record ResolvedObjectStorageProvider(
    String code,
    ObjectStorageProperties.ProviderProperties properties
) {
}
