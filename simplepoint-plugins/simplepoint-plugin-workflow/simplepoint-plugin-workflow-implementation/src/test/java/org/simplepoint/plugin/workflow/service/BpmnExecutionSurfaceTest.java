package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.WorkflowModelingException;

/** Parses inert fixture names; no listener class or expression is ever executed by these tests. */
class BpmnExecutionSurfaceTest {
  private final BpmnDefinitionValidator validator = new BpmnDefinitionValidator();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://activiti.org/bpmn",
        "http://camunda.org/schema/1.0/bpmn",
        "urn:test:metadata",
        ""
      })
  void nativeListenerAliasesCannotBypassPlatformExecutionPolicy(String namespace) {
    String listener =
        "<executionListener xmlns=\""
            + namespace
            + "\" event=\"start\" class=\"example.NeverExecuted\"/>";
    String source =
        source()
            .replace(
                "<bpmn:startEvent",
                "<bpmn:extensionElements>"
                    + listener
                    + "</bpmn:extensionElements><bpmn:startEvent");
    assertThat(convert(source).getMainProcess().getExecutionListeners())
        .singleElement()
        .satisfies(
            value -> assertThat(value.getImplementation()).isEqualTo("example.NeverExecuted"));
    assertThatThrownBy(() -> validator.validate(source, "approval"))
        .isInstanceOf(WorkflowException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://activiti.org/bpmn", "http://camunda.org/schema/1.0/bpmn"})
  void alternateEngineAttributesCannotSkipExpressionValidation(String namespace) {
    String source =
        source()
            .replace(
                "<bpmn:userTask",
                "<bpmn:userTask xmlns:compat=\""
                    + namespace
                    + "\" compat:dueDate=\"${execution.variables}\"");
    var task = (UserTask) convert(source).getMainProcess().getFlowElement("review");
    assertThat(task.getDueDate()).isEqualTo("${execution.variables}");
    assertThatThrownBy(() -> validator.validate(source, "approval"))
        .isInstanceOf(WorkflowException.class);
  }

  @Test
  void undocumentedEngineAttributesAreRejectedWithoutReflectingTheirValues() {
    for (String attribute :
        java.util.List.of("skipExpression", "taskIdVariableName", "triggerable")) {
      String source =
          source()
              .replace(
                  "<bpmn:userTask",
                  "<bpmn:userTask flowable:" + attribute + "=\"sensitive-value\"");
      assertThatThrownBy(() -> validator.validate(source, "approval"))
          .isInstanceOfSatisfying(
              WorkflowModelingException.class,
              error -> {
                assertThat(error.getMessageKey()).isEqualTo("workflow.modeling.executionAttribute");
                assertThat(error.getElementId()).isEqualTo("review");
                assertThat(error.getMessage()).doesNotContain("sensitive-value");
              });
    }
  }

  @Test
  void canonicalDiagramAndExplicitAsyncRemainSupportedButForeignSemanticElementsDoNot() {
    validator.validate(
        source().replace("<bpmn:userTask", "<bpmn:userTask flowable:async=\"true\""), "approval");
    for (String inserted :
        java.util.List.of(
            "<unknown id=\"foreign\"/>",
            "<dc:serviceTask id=\"foreign\"/>",
            "<bpmn:userTask id=\"foreign\" flowable:assignee=\"actor\" flowable:async=\"invalid\"/>")) {
      assertThatThrownBy(
              () ->
                  validator.validate(
                      source().replace("</bpmn:process>", inserted + "</bpmn:process>"),
                      "approval"))
          .isInstanceOf(WorkflowModelingException.class);
    }
  }

  private static String source() {
    return WorkflowBpmnTemplates.approval("approval", "Approval");
  }

  private static BpmnModel convert(String xml) {
    return new BpmnXMLConverter()
        .convertToBpmnModel(
            () -> new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), false, false);
  }
}
