/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.api.model;

import lombok.Data;

/**
 * Upload request options.
 */
@Data
public class ObjectStorageUploadRequest {

  private String providerCode;

  private String directory;

  private String objectKey;

  private String fileName;

  private String sourceServiceName;
}
