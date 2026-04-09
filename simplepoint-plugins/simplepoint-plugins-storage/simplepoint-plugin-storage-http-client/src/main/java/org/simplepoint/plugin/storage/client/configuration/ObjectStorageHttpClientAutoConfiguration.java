/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.storage.client.configuration;

import org.simplepoint.plugin.storage.client.properties.ObjectStorageRemoteProperties;
import org.simplepoint.plugin.storage.client.service.ObjectStorageRemoteService;
import org.simplepoint.plugin.storage.client.service.RestClientObjectStorageRemoteService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Auto-configuration for the remote object-storage HTTP client.
 */
@AutoConfiguration
@ConditionalOnClass(RestClient.class)
@EnableConfigurationProperties(ObjectStorageRemoteProperties.class)
@ConditionalOnProperty(prefix = ObjectStorageRemoteProperties.PREFIX, name = "service-name")
public class ObjectStorageHttpClientAutoConfiguration {

  @Bean
  @LoadBalanced
  @ConditionalOnMissingBean(name = "objectStorageRestClientBuilder")
  public RestClient.Builder objectStorageRestClientBuilder() {
    return RestClient.builder();
  }

  @Bean
  @ConditionalOnMissingBean
  public ObjectStorageRemoteService objectStorageRemoteService(
      final RestClient.Builder objectStorageRestClientBuilder,
      final ObjectStorageRemoteProperties properties
  ) {
    return new RestClientObjectStorageRemoteService(objectStorageRestClientBuilder, properties);
  }
}
