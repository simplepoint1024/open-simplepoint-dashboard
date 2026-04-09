/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.api.spi;

import java.io.InputStream;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Provider upload request.
 */
@Getter
@AllArgsConstructor
public class ObjectStorageWriteRequest {

  private final String bucket;

  private final String objectKey;

  private final String originalFileName;

  private final String contentType;

  private final long contentLength;

  private final Map<String, String> metadata;

  private final InputStream inputStream;
}
