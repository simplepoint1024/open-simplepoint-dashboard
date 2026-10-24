package org.simplepoint.workflow.server.config;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.plugin.workflow.config.WorkflowEngineCustomizer;
import org.simplepoint.plugin.workflow.config.WorkflowEngineFactory;
import org.simplepoint.plugin.workflow.config.WorkflowEngineServices;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Adapts the original single-store host; explicit legacy schema initialization remains supported.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnMissingBean(ModulePlan.class)
@Import(WorkflowEngineServices.class)
public class LegacyWorkflowEngineConfiguration {
  @Bean
  SpringProcessEngineConfiguration legacyWorkflowProcessEngineConfiguration(
      DataSource source,
      PlatformTransactionManager manager,
      Environment environment,
      ObjectProvider<WorkflowEngineCustomizer> customizers)
      throws SQLException {
    return WorkflowEngineFactory.create(source, manager, environment, customizers, true);
  }
}
