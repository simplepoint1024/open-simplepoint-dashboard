/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.api.spi;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Provider upload result.
 */
@Getter
@AllArgsConstructor
public class ObjectStorageWriteResult {

  private final String bucket;

  private final String objectKey;

  private final String eTag;

  private final String accessUrl;
}
