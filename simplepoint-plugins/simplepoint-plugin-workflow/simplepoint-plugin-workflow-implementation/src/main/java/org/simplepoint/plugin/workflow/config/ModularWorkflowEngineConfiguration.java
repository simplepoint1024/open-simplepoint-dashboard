package org.simplepoint.plugin.workflow.config;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

/** Starts the selected workflow engine on its owned store; native installation stays separate. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(ModulePlan.class)
@Import(WorkflowEngineServices.class)
public class ModularWorkflowEngineConfiguration {
  @Bean
  SpringProcessEngineConfiguration workflowProcessEngineConfiguration(
      ModulePlan modules,
      ModuleStorageBindings bindings,
      ConfigurableListableBeanFactory beans,
      Environment environment,
      ObjectProvider<WorkflowEngineCustomizer> customizers)
      throws SQLException {
    if (modules.modules().stream().noneMatch(module -> module.id().equals("workflow"))) {
      throw new ModuleCompositionException("UNSELECTED_WORKFLOW_ENGINE", "workflow");
    }
    var binding = bindings.require("workflow");
    return WorkflowEngineFactory.create(
        beans.getBean(binding.dataSource(), DataSource.class),
        beans.getBean(binding.transactionManager(), PlatformTransactionManager.class),
        environment,
        customizers,
        false);
  }
}
