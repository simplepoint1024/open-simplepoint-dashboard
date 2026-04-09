/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.api.spi;

import java.io.InputStream;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Provider download result.
 */
@Getter
@AllArgsConstructor
public class ObjectStorageReadResult {

  private final InputStream inputStream;

  private final String contentType;

  private final Long contentLength;

  private final String fileName;
}
