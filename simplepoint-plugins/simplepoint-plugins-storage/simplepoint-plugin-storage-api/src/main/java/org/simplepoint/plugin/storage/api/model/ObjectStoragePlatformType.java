/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.api.model;

/**
 * Supported built-in object-storage platform types.
 */
public enum ObjectStoragePlatformType {
  MINIO,
  S3,
  ALIYUN_OSS,
  TENCENT_COS,
  CEPH
}
