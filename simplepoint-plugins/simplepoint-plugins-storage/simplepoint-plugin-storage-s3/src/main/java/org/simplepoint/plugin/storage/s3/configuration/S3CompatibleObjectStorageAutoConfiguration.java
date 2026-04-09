/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.s3.configuration;

import org.simplepoint.plugin.storage.api.spi.ObjectStorageDriver;
import org.simplepoint.plugin.storage.s3.support.S3CompatibleObjectStorageDriver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Auto-configuration for the built-in S3-compatible storage driver.
 */
@AutoConfiguration
@ConditionalOnClass(S3Client.class)
public class S3CompatibleObjectStorageAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(name = "s3CompatibleObjectStorageDriver")
  public ObjectStorageDriver s3CompatibleObjectStorageDriver() {
    return new S3CompatibleObjectStorageDriver();
  }
}
