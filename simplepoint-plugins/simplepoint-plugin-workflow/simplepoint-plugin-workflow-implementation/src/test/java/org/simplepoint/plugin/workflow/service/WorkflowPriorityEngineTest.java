package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** The browser fixture is checked against real tasks, including each native numeric subtype. */
class WorkflowPriorityEngineTest {

  private static ProcessEngine engine;

  @BeforeAll
  static void startEngine() {
    engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
        .setEngineName("priority-literals-test")
        .setJdbcUrl("jdbc:h2:mem:priority-literals;DB_CLOSE_DELAY=-1")
        .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
        .setAsyncExecutorActivate(false).buildProcessEngine();
  }

  @AfterAll
  static void closeEngine() {
    if (engine != null) {
      engine.close();
    }
  }

  @TestFactory
  Stream<DynamicTest> realPriorityConversionMatchesTheSharedCases() throws Exception {
    List<PriorityCase> cases;
    try (var input = getClass().getResourceAsStream("/workflow-expressions/priorityLiterals.fixture.json")) {
      assertThat(input).isNotNull();
      cases = new ObjectMapper().readValue(input, new TypeReference<List<PriorityCase>>() {});
    }
    return cases.stream().map(item -> DynamicTest.dynamicTest(item.source(), () -> assertEnginePriority(item)));
  }

  private void assertEnginePriority(final PriorityCase item) {
    String attribute = item.source().replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    String xml = WorkflowBpmnTemplates.approval("nativePriority", "Priority")
        .replace("<bpmn:userTask ", "<bpmn:userTask flowable:priority=\"" + attribute + "\" ");
    var deployment = engine.getRepositoryService().createDeployment().addString("priority.bpmn20.xml", xml).deploy();
    try {
      if (item.issue() != null) {
        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("nativePriority", Map.of("initiator", "actor")))
            .isInstanceOf(FlowableException.class);
      } else {
        var process = engine.getRuntimeService().startProcessInstanceByKey("nativePriority", Map.of("initiator", "actor"));
        var task = engine.getTaskService().createTaskQuery().processInstanceId(process.getId()).singleResult();
        assertThat(task.getPriority()).isEqualTo(item.defaultValue() == null ? Integer.valueOf(item.value()) : item.defaultValue());
      }
    } finally {
      engine.getRepositoryService().deleteDeployment(deployment.getId(), true);
    }
  }

  private record PriorityCase(String source, String value, String issue, Integer defaultValue) {
  }
}
