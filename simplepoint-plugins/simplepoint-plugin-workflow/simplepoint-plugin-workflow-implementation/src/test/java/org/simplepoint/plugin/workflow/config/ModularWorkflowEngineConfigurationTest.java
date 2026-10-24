package org.simplepoint.plugin.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

class ModularWorkflowEngineConfigurationTest {
  private final ModulePlan modules =
      new ModuleCatalog(List.of(WorkflowEngineConfiguration.class)).plan(List.of("workflow"));
  private final ModuleStorageBindings bindings =
      new ModuleStorageBindings(
          Map.of(
              "workflow",
              new ModuleStorageBindings.Binding(
                  "workflow", "workflowSource", "emf", "em", "workflowTx", "jdbc")));

  @Test
  void ownedBindingWinsWithoutPrimarySelectionAndRuntimeDefaultsRemainSafe() throws Exception {
    var beans = beans();
    var configuration = configure(beans, Map.of());
    assertThat(
            ((TransactionAwareDataSourceProxy) configuration.getDataSource()).getTargetDataSource())
        .isSameAs(beans.getBean("workflowSource"));
    assertThat(configuration.getTransactionManager()).isSameAs(beans.getBean("workflowTx"));
    assertThat(configuration.getDatabaseSchemaUpdate()).isEqualTo("false");
    assertThat(configuration.getDatabaseSchema()).isEqualTo("PUBLIC");
    assertThat(configuration.getHistoryLevel().getKey()).isEqualTo("audit");
    assertThat(configuration.getBeans()).isEmpty();
    assertThat(configuration.isAsyncExecutorActivate()).isTrue();
  }

  @Test
  void installationOptionsSchemaOverrideAndInsufficientHistoryAreRejected() {
    for (var options :
        List.of(
            Map.of("flowable.database-schema-update", "true"),
            Map.of("flowable.database-schema-update", "create-drop"),
            Map.of("flowable.check-process-definitions", "true"),
            Map.of("flowable.database-schema", "another"),
            Map.of("flowable.history-level", "none"))) {
      assertThatThrownBy(() -> configure(beans(), options))
          .isInstanceOf(org.simplepoint.platform.composition.ModuleCompositionException.class);
    }
  }

  @Test
  void customizersCannotRedirectStorageOrExposeApplicationBeans() {
    for (WorkflowEngineCustomizer customizer :
        List.<WorkflowEngineCustomizer>of(
            configuration ->
                configuration.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:other")),
            configuration -> configuration.setDatabaseSchemaUpdate("true"),
            configuration -> configuration.setBeans(Map.of("privateBean", new Object())))) {
      var beans = beans();
      beans.registerSingleton("unsafe", customizer);
      assertThatThrownBy(() -> configure(beans, Map.of()))
          .isInstanceOf(org.simplepoint.platform.composition.ModuleCompositionException.class);
    }
  }

  private SpringProcessEngineConfiguration configure(
      DefaultListableBeanFactory beans, Map<String, String> options) throws Exception {
    var environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("test", Map.copyOf(options)));
    return new ModularWorkflowEngineConfiguration()
        .workflowProcessEngineConfiguration(
            modules,
            bindings,
            beans,
            environment,
            beans.getBeanProvider(WorkflowEngineCustomizer.class));
  }

  private static DefaultListableBeanFactory beans() {
    var beans = new DefaultListableBeanFactory();
    var source =
        new DriverManagerDataSource("jdbc:h2:mem:workflow-config;DB_CLOSE_DELAY=-1", "sa", "");
    beans.registerSingleton("workflowSource", source);
    beans.registerSingleton("workflowTx", new DataSourceTransactionManager(source));
    beans.registerSingleton("foreignSource", new DriverManagerDataSource("jdbc:h2:mem:foreign"));
    beans.registerSingleton(
        "foreignTx",
        new DataSourceTransactionManager(beans.getBean("foreignSource", DataSource.class)));
    return beans;
  }
}
