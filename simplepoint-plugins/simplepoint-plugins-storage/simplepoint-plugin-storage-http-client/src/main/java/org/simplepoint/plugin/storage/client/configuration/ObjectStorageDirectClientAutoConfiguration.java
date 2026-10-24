package org.simplepoint.plugin.storage.client.configuration;

import org.simplepoint.plugin.storage.client.properties.ObjectStorageRemoteProperties;
import org.simplepoint.plugin.storage.client.service.ObjectStorageRemoteService;
import org.simplepoint.plugin.storage.client.service.RestClientObjectStorageRemoteService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/** Explicit HTTP endpoint for deployments that do not use service discovery. */
@AutoConfiguration(before = ObjectStorageHttpClientAutoConfiguration.class)
@EnableConfigurationProperties(ObjectStorageRemoteProperties.class)
@ConditionalOnProperty(prefix = ObjectStorageRemoteProperties.PREFIX, name = "base-url")
public class ObjectStorageDirectClientAutoConfiguration {
  /** Keeps normal HTTP authentication and authorization even for a co-located storage owner. */
  @Bean
  @ConditionalOnMissingBean(ObjectStorageRemoteService.class)
  ObjectStorageRemoteService directObjectStorageRemoteService(ObjectStorageRemoteProperties properties) {
    return new RestClientObjectStorageRemoteService(RestClient.builder(), properties);
  }
}
