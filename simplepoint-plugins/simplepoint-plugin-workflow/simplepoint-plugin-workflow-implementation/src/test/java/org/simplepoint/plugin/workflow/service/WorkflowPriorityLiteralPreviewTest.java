package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.simplepoint.plugin.workflow.api.WorkflowException;

/** Shared fixtures prove preview and actual draft validation use the same native field rules. */
class WorkflowPriorityLiteralPreviewTest {

  private final WorkflowPriorityLiteralPreview service = new WorkflowPriorityLiteralPreview();

  @TestFactory
  Stream<DynamicTest> scalarPreviewsAndDraftValidationAgree() throws Exception {
    List<PriorityCase> cases;
    try (var input = getClass().getResourceAsStream("/workflow-expressions/priorityLiterals.fixture.json")) {
      assertThat(input).isNotNull();
      cases = new ObjectMapper().readValue(input, new TypeReference<List<PriorityCase>>() {});
    }
    return cases.stream().map(item -> DynamicTest.dynamicTest(item.source(), () -> assertPreview(item)));
  }

  private void assertPreview(final PriorityCase item) {
    var result = service.preview(item.source());
    assertThat(result.value()).isEqualTo(item.value());
    assertThat(result.issue()).isEqualTo(item.issue());
    String attribute = item.source().replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    String xml = WorkflowBpmnTemplates.approval("priorityContract", "Priority")
        .replace("<bpmn:userTask ", "<bpmn:userTask flowable:priority=\"" + attribute + "\" ");
    if (item.issue() == null) {
      assertThat(new BpmnDefinitionValidator().validate(xml, "priorityContract")).isNotNull();
    } else {
      assertThatThrownBy(() -> new BpmnDefinitionValidator().validate(xml, "priorityContract"))
          .isInstanceOf(WorkflowException.class).hasMessageContaining("优先级常量");
    }
  }

  @Test
  void noVariablesMethodsOrCompoundExpressionsAreEverEvaluated() {
    for (String source : List.of("${number}", "${task.getClass()}", "${1 + 2}", "${1}${2}", "prefix${1}", "#{1}",
        "${'safe' + '1'}", "${'1'} trailing", "${" + "1".repeat(500) + "}")) {
      assertThat(service.isLiteral(source)).as(source).isFalse();
      assertThatThrownBy(() -> service.preview(source)).isInstanceOf(WorkflowException.class);
    }
    assertThat(service.preview(null).value()).isNull();
    assertThat(service.preview("  ").value()).isNull();
  }

  private record PriorityCase(String source, String value, String issue, Integer defaultValue) {
  }
}
