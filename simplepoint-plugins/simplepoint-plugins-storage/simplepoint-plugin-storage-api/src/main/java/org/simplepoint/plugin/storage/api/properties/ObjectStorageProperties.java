/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.api.properties;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.simplepoint.plugin.storage.api.model.ObjectStoragePlatformType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Property-driven object-storage provider definitions.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = ObjectStorageProperties.PREFIX)
public class ObjectStorageProperties {

  public static final String PREFIX = "simplepoint.storage";

  private String defaultProvider;

  private Map<String, ProviderProperties> providers = new LinkedHashMap<>();

  /**
   * Provider-specific properties.
   */
  @Data
  public static class ProviderProperties {

    private String name;

    private ObjectStoragePlatformType type;

    private String endpoint;

    private String region = "us-east-1";

    private String accessKey;

    private String secretKey;

    private String bucket;

    private String basePath;

    private Boolean pathStyleAccess;

    private Boolean enabled = true;

    private String publicBaseUrl;
  }
}
