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
import org.simplepoint.plugin.workflow.api.WorkflowException;

/** Task due dates accept temporal values or calendar text, never numeric or boolean scalar results. */
class WorkflowDueDateEngineTest {

  private static ProcessEngine engine;

  @BeforeAll
  static void startEngine() {
    engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
        .setEngineName("due-date-literals-test")
        .setJdbcUrl("jdbc:h2:mem:due-date-literals;DB_CLOSE_DELAY=-1")
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
  Stream<DynamicTest> realTaskDueDateTypesMatchTheSharedCases() throws Exception {
    return cases().stream().map(item -> DynamicTest.dynamicTest(item.source(), () -> assertEngineDueDate(item)));
  }

  @TestFactory
  Stream<DynamicTest> draftValidationRejectsKnownImpossibleDueDateTypes() throws Exception {
    return cases().stream().map(item -> DynamicTest.dynamicTest(item.source(), () -> {
      if (item.typeIssue()) {
        assertThatThrownBy(() -> new BpmnDefinitionValidator().validate(xml(item.source()), "nativeDueDate"))
            .isInstanceOf(WorkflowException.class).hasMessageContaining("到期时间");
      } else {
        assertThat(new BpmnDefinitionValidator().validate(xml(item.source()), "nativeDueDate")).isNotNull();
      }
    }));
  }

  private List<DueDateCase> cases() throws Exception {
    try (var input = getClass().getResourceAsStream("/workflow-expressions/dueDateLiterals.fixture.json")) {
      assertThat(input).isNotNull();
      return new ObjectMapper().readValue(input, new TypeReference<List<DueDateCase>>() {});
    }
  }

  private void assertEngineDueDate(final DueDateCase item) {
    var deployment = engine.getRepositoryService().createDeployment().addString("due-date.bpmn20.xml", xml(item.source())).deploy();
    try {
      Map<String, Object> variables = Map.of("initiator", "actor", "due", "2026-10-12T12:00:00Z");
      if (item.typeIssue()) {
        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("nativeDueDate", variables))
            .isInstanceOf(FlowableException.class);
      } else {
        var process = engine.getRuntimeService().startProcessInstanceByKey("nativeDueDate", variables);
        var task = engine.getTaskService().createTaskQuery().processInstanceId(process.getId()).singleResult();
        if (item.noDate()) {
          assertThat(task.getDueDate()).isNull();
        } else {
          assertThat(task.getDueDate()).isNotNull();
          if (item.instant() != null) {
            assertThat(task.getDueDate().toInstant().toString()).isEqualTo(item.instant());
          }
        }
      }
    } finally {
      engine.getRepositoryService().deleteDeployment(deployment.getId(), true);
    }
  }

  private String xml(final String source) {
    String attribute = source.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    return WorkflowBpmnTemplates.approval("nativeDueDate", "Due date")
        .replace("<bpmn:userTask ", "<bpmn:userTask flowable:dueDate=\"" + attribute + "\" ");
  }

  private record DueDateCase(String source, boolean typeIssue, boolean noDate, String instant) {
  }
}
