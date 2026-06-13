package com.simplepoint.service.router.consul;

import com.simplepoint.service.router.metadata.RoutedServiceMetadata;
import com.simplepoint.service.router.registry.CapabilityRegistry;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.consul.serviceregistry.ConsulRegistration;
import org.springframework.cloud.consul.serviceregistry.ConsulRegistrationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Consul integration for Service Router.
 */
@AutoConfiguration
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ConsulRegistration.class)
public class ServiceRouterConsulAutoConfiguration {

  /**
   * Registers routed service -> version/application mapping into Consul metadata.
   *
   * @param applicationName spring.application.name
   * @param registryProvider local capability registry provider
   * @return consul registration customizer
   */
  @Bean
  public ConsulRegistrationCustomizer serviceRouterConsulRegistrationCustomizer(
      @Value("${spring.application.name:application}") final String applicationName,
      final ObjectProvider<CapabilityRegistry> registryProvider
  ) {
    return registration -> {
      CapabilityRegistry registry = registryProvider.getIfAvailable();
      if (registry == null) {
        return;
      }
      List<RoutedServiceMetadata> capabilities = registry.capabilities();
      String mergedCapabilities = mergeCapabilities(capabilities);
      String mappings = buildMappings(capabilities, applicationName);
      if (!mergedCapabilities.isBlank()) {
        registration.getMetadata().put(ServiceRouterDiscoveryMetadata.CAPABILITIES, mergedCapabilities);
      }
      if (!mappings.isBlank()) {
        registration.getMetadata().put(ServiceRouterDiscoveryMetadata.MAPPINGS, mappings);
      }
    };
  }

  private static String mergeCapabilities(final List<RoutedServiceMetadata> capabilities) {
    LinkedHashSet<String> capabilityList = new LinkedHashSet<>();
    for (RoutedServiceMetadata capability : capabilities) {
      if (StringUtils.hasText(capability.name()) && StringUtils.hasText(capability.version())) {
        capabilityList.add(capability.name() + ":" + capability.version());
      }
    }
    return String.join(",", capabilityList);
  }

  private static String buildMappings(
      final List<RoutedServiceMetadata> capabilities,
      final String applicationName
  ) {
    LinkedHashSet<String> mappingEntries = new LinkedHashSet<>();
    for (RoutedServiceMetadata capability : capabilities) {
      if (StringUtils.hasText(capability.name()) && StringUtils.hasText(capability.version())) {
        mappingEntries.add(capability.name() + ":" + capability.version() + "=" + applicationName);
      }
    }
    return String.join(",", mappingEntries);
  }
}
