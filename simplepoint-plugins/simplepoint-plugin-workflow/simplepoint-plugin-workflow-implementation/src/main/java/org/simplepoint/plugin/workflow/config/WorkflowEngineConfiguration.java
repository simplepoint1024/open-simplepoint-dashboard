package org.simplepoint.plugin.workflow.config;

import java.util.Map;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Limits tenant-authored expressions to process variables, not the application's Spring beans. */
@Configuration(proxyBeanMethods = false)
public class WorkflowEngineConfiguration {

  /** Flowable's Spring integration exposes all beans when this map is null; an empty map exposes none. */
  @Bean
  public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> workflowExpressionIsolation() {
    return configuration -> configuration.setBeans(Map.of());
  }
}
