package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.ProcessEngine;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.workflow.config.WorkflowEngineConfiguration;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Runs real Flowable/JUEL against a Spring context which contains a deliberately private marker bean. */
class WorkflowExpressionIsolationTest {

  @Test
  void processVariablesWorkButApplicationBeansCannotBeResolved() {
    var source = new DriverManagerDataSource("jdbc:h2:mem:expression-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    try (var context = new StaticApplicationContext()) {
      context.getBeanFactory().registerSingleton("privateApplicationBean", Map.of("secret", "must-not-resolve"));
      var configuration = new SpringProcessEngineConfiguration();
      configuration.setDataSource(source);
      configuration.setTransactionManager(new DataSourceTransactionManager(source));
      configuration.setApplicationContext(context);
      configuration.setDatabaseSchemaUpdate("true");
      configuration.setAsyncExecutorActivate(false);
      new WorkflowEngineConfiguration().workflowExpressionIsolation().configure(configuration);
      ProcessEngine engine = configuration.buildProcessEngine();
      try {
        String xml = WorkflowBpmnTemplates.approval("safeProcess", "Safe process");
        var safe = engine.getRepositoryService().createDeployment().addString("safe.bpmn20.xml", xml).deploy();
        engine.getRuntimeService().startProcessInstanceByKey("safeProcess", Map.of("initiator", "actor"));
        assertThat(engine.getTaskService().createTaskQuery().taskAssignee("actor").count()).isEqualTo(1);
        assertThat(configuration.getExpressionManager().getBeans()).isEmpty();
        var blocked = engine.getRepositoryService().createDeployment().addString("blocked.bpmn20.xml",
            WorkflowBpmnTemplates.approval("blockedProcess", "Blocked process")
                .replace("${initiator}", "${privateApplicationBean.secret}")).deploy();
        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("blockedProcess"))
            .isInstanceOf(FlowableException.class);
        assertThat(engine.getTaskService().createTaskQuery().taskAssignee("must-not-resolve").count()).isZero();
        engine.getRepositoryService().deleteDeployment(safe.getId(), true);
        engine.getRepositoryService().deleteDeployment(blocked.getId(), true);
      } finally {
        engine.close();
      }
    }
  }
}
