/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.api.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public provider summary returned to the UI.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageProviderDefinition {

  private String code;

  private String name;

  private ObjectStoragePlatformType type;

  private String endpoint;

  private String bucket;

  private Boolean defaultProvider;
}
