/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.service.initialize;

import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageProviderConfig;
import org.simplepoint.plugin.storage.api.model.ObjectStoragePlatformType;
import org.simplepoint.plugin.storage.api.properties.ObjectStorageProperties;
import org.simplepoint.plugin.storage.api.repository.ObjectStorageProviderConfigRepository;
import org.simplepoint.plugin.storage.api.service.ObjectStorageProviderConfigService;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Initializes the Docker/local MinIO provider during platform bootstrap.
 */
@Component
public class ObjectStorageMinioInitializer {

  private static final String CONTRIBUTION_KEY = "docker-compose-minio-provider";

  /**
   * Registers an optional MinIO provider contribution.
   *
   * @param properties        object-storage configuration properties
   * @param repository        provider configuration repository
   * @param providerService   provider configuration service
   * @return the platform bootstrap contribution
   */
  @Bean
  public PlatformBootstrapContribution objectStorageMinioBootstrapContribution(
      final ObjectStorageProperties properties,
      final ObjectStorageProviderConfigRepository repository,
      final ObjectStorageProviderConfigService providerService
  ) {
    return () -> {
      ObjectStorageProperties.MinioBootstrapProperties minio = minio(properties);
      if (minio == null || !minio.isEnabled()) {
        return null;
      }
      return BootstrapContribution.versioned(
          "storage",
          "object-storage-provider",
          CONTRIBUTION_KEY,
          "1",
          350,
          () -> initializeMinioProvider(minio, repository, providerService)
      );
    };
  }

  void initializeMinioProvider(
      final ObjectStorageProperties.MinioBootstrapProperties minio,
      final ObjectStorageProviderConfigRepository repository,
      final ObjectStorageProviderConfigService providerService
  ) {
    if (repository.findActiveByCode(minio.getCode()).isPresent()) {
      return;
    }

    ObjectStorageProviderConfig provider = new ObjectStorageProviderConfig();
    provider.setCode(minio.getCode());
    provider.setName(minio.getName());
    provider.setType(ObjectStoragePlatformType.MINIO);
    provider.setEndpoint(minio.getEndpoint());
    provider.setRegion(minio.getRegion());
    provider.setAccessKey(minio.getAccessKey());
    provider.setSecretKey(minio.getSecretKey());
    provider.setBucket(minio.getBucket());
    provider.setBasePath(minio.getBasePath());
    provider.setPathStyleAccess(Boolean.TRUE);
    provider.setPublicBaseUrl(null);
    provider.setEnabled(Boolean.TRUE);
    provider.setDefaultProvider(
        minio.isDefaultProvider() && repository.findDefaultEnabled().isEmpty()
    );
    provider.setDescription(minio.getDescription());
    providerService.create(provider);
  }

  private ObjectStorageProperties.MinioBootstrapProperties minio(
      final ObjectStorageProperties properties
  ) {
    if (properties == null || properties.getBootstrap() == null) {
      return null;
    }
    return properties.getBootstrap().getMinio();
  }
}
