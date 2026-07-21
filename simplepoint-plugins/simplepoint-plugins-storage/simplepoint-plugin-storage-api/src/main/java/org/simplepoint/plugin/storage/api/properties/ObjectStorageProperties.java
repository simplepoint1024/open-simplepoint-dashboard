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

  private String credentialEncryptionKey;

  private String defaultProvider;

  private Map<String, ProviderProperties> providers = new LinkedHashMap<>();

  private BootstrapProperties bootstrap = new BootstrapProperties();

  /**
   * Bootstrap settings for object-storage providers.
   */
  @Data
  public static class BootstrapProperties {

    private MinioBootstrapProperties minio = new MinioBootstrapProperties();
  }

  /**
   * Docker/local MinIO provider initialized during platform bootstrap.
   */
  @Data
  public static class MinioBootstrapProperties {

    private boolean enabled;

    private String code = "minio";

    private String name = "Local MinIO";

    private String endpoint = "http://minio:9000";

    private String region = "us-east-1";

    private String accessKey;

    private String secretKey;

    private String bucket = "simplepoint";

    private String basePath;

    private boolean defaultProvider = true;

    private String description = "Docker Compose local MinIO initialized by the platform";
  }

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
