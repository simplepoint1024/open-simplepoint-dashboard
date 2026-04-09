/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.client.support;

import java.io.InputStream;
import org.springframework.core.io.InputStreamResource;

/**
 * InputStreamResource that keeps a filename and content length.
 */
public class NamedInputStreamResource extends InputStreamResource {

  private final String filename;

  private final long contentLength;

  public NamedInputStreamResource(final InputStream inputStream, final String filename, final long contentLength) {
    super(inputStream);
    this.filename = filename;
    this.contentLength = contentLength;
  }

  @Override
  public String getFilename() {
    return filename;
  }

  @Override
  public long contentLength() {
    return contentLength;
  }
}
