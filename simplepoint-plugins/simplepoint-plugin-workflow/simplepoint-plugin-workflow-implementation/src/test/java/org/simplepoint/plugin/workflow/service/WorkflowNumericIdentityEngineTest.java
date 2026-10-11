package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Stream;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Resolves actual task identities, not merely a standalone expression factory. */
class WorkflowNumericIdentityEngineTest {

  private static ProcessEngine engine;

  @BeforeAll
  static void startEngine() {
    engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
        .setJdbcUrl("jdbc:h2:mem:numeric-identities;DB_CLOSE_DELAY=-1")
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
  Stream<DynamicTest> previewMatchesRealAssigneeAndCandidateIdentityLinks() throws Exception {
    List<LiteralCase> cases;
    try (var input = getClass().getResourceAsStream("/workflow-expressions/numericLiterals.fixture.json")) {
      assertThat(input).isNotNull();
      cases = new ObjectMapper().readValue(input, new TypeReference<List<LiteralCase>>() {});
    }
    return cases.stream().flatMap(item -> List.of("${" + item.literal() + "}", "  ${" + item.literal() + "}  ",
        "${ " + item.literal() + " }").stream()
        .map(expression -> DynamicTest.dynamicTest(expression, () -> assertEngineIdentity(item, expression))));
  }

  private void assertEngineIdentity(final LiteralCase item, final String expression) {
    String xml = WorkflowBpmnTemplates.approval("numericIdentity", "Numeric identity")
        .replace("flowable:assignee=\"${initiator}\"", "flowable:assignee=\"" + expression + "\"")
        .replace("<bpmn:userTask ", "<bpmn:userTask flowable:candidateUsers=\"" + expression
            + "\" flowable:candidateGroups=\"" + expression + "\" ");
    assertThat(xml).contains("flowable:assignee=\"" + expression + "\"");
    var deployment = engine.getRepositoryService().createDeployment().addString("numeric.bpmn20.xml", xml).deploy();
    try {
      var process = engine.getRuntimeService().startProcessInstanceByKey("numericIdentity");
      var task = engine.getTaskService().createTaskQuery().processInstanceId(process.getId()).singleResult();
      String preview = new WorkflowNumericLiteralPreview().preview(expression);
      assertThat(preview).isEqualTo(item.value());
      assertThat(task.getAssignee()).isEqualTo(preview);
      var links = engine.getTaskService().getIdentityLinksForTask(task.getId());
      assertThat(links.stream().filter(link -> "candidate".equals(link.getType())).map(link -> link.getUserId()))
          .contains(preview);
      assertThat(links.stream().filter(link -> "candidate".equals(link.getType())).map(link -> link.getGroupId()))
          .contains(preview);
    } finally {
      engine.getRepositoryService().deleteDeployment(deployment.getId(), true);
    }
  }

  private record LiteralCase(String literal, String value) {
  }
}
