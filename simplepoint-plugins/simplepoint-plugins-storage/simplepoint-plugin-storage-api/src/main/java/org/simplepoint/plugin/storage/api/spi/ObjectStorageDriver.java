/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.api.spi;

import org.simplepoint.plugin.storage.api.model.ObjectStoragePlatformType;
import org.simplepoint.plugin.storage.api.properties.ObjectStorageProperties;

/**
 * SPI for pluggable object-storage providers.
 */
public interface ObjectStorageDriver {

  /**
   * Whether this driver supports the given platform type.
   *
   * @param type platform type
   * @return true when supported
   */
  boolean supports(ObjectStoragePlatformType type);

  /**
   * Uploads an object.
   *
   * @param properties provider properties
   * @param request    write request
   * @return write result
   */
  ObjectStorageWriteResult write(
      ObjectStorageProperties.ProviderProperties properties,
      ObjectStorageWriteRequest request
  );

  /**
   * Reads an object.
   *
   * @param properties provider properties
   * @param bucket     bucket name
   * @param objectKey  object key
   * @param fileName   file name used for download
   * @return read result
   */
  ObjectStorageReadResult read(
      ObjectStorageProperties.ProviderProperties properties,
      String bucket,
      String objectKey,
      String fileName
  );

  /**
   * Deletes an object.
   *
   * @param properties provider properties
   * @param bucket     bucket name
   * @param objectKey  object key
   */
  void delete(
      ObjectStorageProperties.ProviderProperties properties,
      String bucket,
      String objectKey
  );
}
