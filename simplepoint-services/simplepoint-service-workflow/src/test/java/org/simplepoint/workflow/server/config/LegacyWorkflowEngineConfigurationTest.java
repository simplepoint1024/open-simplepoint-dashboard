package org.simplepoint.workflow.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.sql.DataSource;
import org.flowable.engine.ProcessEngine;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.workflow.config.WorkflowEngineConfiguration;
import org.simplepoint.plugin.workflow.config.WorkflowEngineCustomizer;
import org.simplepoint.plugin.workflow.service.WorkflowActivityBehaviorFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

class LegacyWorkflowEngineConfigurationTest {
  @Test
  void originalHostUsesSharedEngineFactoryAndHonorsItsEngineProperties() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:legacy-engine-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    new ApplicationContextRunner()
        .withUserConfiguration(LegacyWorkflowEngineConfiguration.class)
        .withBean(DataSource.class, () -> source)
        .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source))
        .withBean(
            WorkflowEngineCustomizer.class,
            () -> new WorkflowEngineConfiguration().workflowExpressionIsolation())
        .withPropertyValues(
            "flowable.database-schema-update=true",
            "flowable.check-process-definitions=false",
            "flowable.async-executor-activate=false",
            "flowable.history-level=full")
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(ProcessEngine.class);
              var configuration = context.getBean(SpringProcessEngineConfiguration.class);
              assertThat(configuration.getBeans()).isEmpty();
              assertThat(configuration.getActivityBehaviorFactory())
                  .isInstanceOf(WorkflowActivityBehaviorFactory.class);
              assertThat(configuration.getHistoryLevel().getKey()).isEqualTo("full");
              assertThat(configuration.getTransactionManager())
                  .isSameAs(context.getBean(PlatformTransactionManager.class));
            });
  }
}
