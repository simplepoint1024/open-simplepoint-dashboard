package org.simplepoint.platform.bootstrap;

import java.util.stream.Collectors;
import org.simplepoint.platform.bootstrap.properties.PlatformBootstrapProperties;
import org.simplepoint.platform.bootstrap.service.PlatformContributionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for platform bootstrap execution.
 */
@AutoConfiguration
@EnableConfigurationProperties(PlatformBootstrapProperties.class)
public class PlatformBootstrapAutoConfiguration {

  /**
   * Creates the platform bootstrap executor.
   *
   * @param serviceName                 the current service name
   * @param contributionServiceProvider contribution registry service provider
   * @param contributionProvider        contribution bean provider
   * @param properties                  bootstrap properties
   * @return the platform bootstrap executor
   */
  @Bean
  public PlatformBootstrapExecutor platformBootstrapExecutor(
      @Value("${spring.application.name:unknown}")
      String serviceName,
      ObjectProvider<PlatformContributionService> contributionServiceProvider,
      ObjectProvider<PlatformBootstrapContribution> contributionProvider,
      PlatformBootstrapProperties properties
  ) {
    return new PlatformBootstrapExecutor(
        serviceName,
        contributionServiceProvider,
        contributionProvider.stream().collect(Collectors.toUnmodifiableSet()),
        properties
    );
  }
}
