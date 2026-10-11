package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.workflow.api.WorkflowException;

class BpmnDefinitionValidatorTest {

  private final BpmnDefinitionValidator validator = new BpmnDefinitionValidator();

  @Test
  void malformedExpressionAndJsonOnlyStringEscapesAreRejectedBeforeDeployment() {
    for (String expression : java.util.List.of("${reason == 'a\\n'}", "${reason == 'a\\u0041'}",
        "${reason == }", "${reason == 'unterminated}", "${reason == 'safe' trailing}")) {
      String xml = WorkflowBpmnTemplates.approval("approval", "Approval")
          .replace("<bpmn:sequenceFlow id=\"flow_review_end\" sourceRef=\"review\" targetRef=\"end\"/>",
              "<bpmn:sequenceFlow id=\"flow_review_end\" sourceRef=\"review\" targetRef=\"end\">"
                  + "<bpmn:conditionExpression>" + expression + "</bpmn:conditionExpression></bpmn:sequenceFlow>");
      assertThatThrownBy(() -> validator.validate(xml, "approval"))
          .isInstanceOf(WorkflowException.class).hasMessageContaining("格式不正确");
    }
  }

  @Test
  void acceptsSupportedApprovalProcess() {
    var result = validator.validate(
        WorkflowBpmnTemplates.approval("leaveApproval", "请假审批"),
        "leaveApproval"
    );

    assertThat(result.key()).isEqualTo("leaveApproval");
    assertThat(result.name()).isEqualTo("请假审批");
  }

  @Test
  void rejectsProcessIdentityDrift() {
    assertThatThrownBy(() -> validator.validate(
        WorkflowBpmnTemplates.approval("leaveApproval", "请假审批"),
        "expenseApproval"
    )).isInstanceOf(WorkflowException.class)
        .hasMessageContaining("必须与流程标识");
  }

  @Test
  void rejectsExecutableJavaSurface() {
    String xml = WorkflowBpmnTemplates.approval("leaveApproval", "请假审批")
        .replace("bpmn:userTask", "bpmn:serviceTask");

    assertThatThrownBy(() -> validator.validate(xml, "leaveApproval"))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("不允许执行");
  }

  @Test
  void rejectsFlowableExecutionListenerExtension() {
    String xml = WorkflowBpmnTemplates.approval("leaveApproval", "请假审批")
        .replace(
            """
                <bpmn:startEvent id="start" name="开始"><bpmn:outgoing>flow_start_review</bpmn:outgoing></bpmn:startEvent>
                """.strip(),
            """
                <bpmn:startEvent id="start" name="开始">
                  <bpmn:extensionElements>
                    <flowable:executionListener event="start" class="example.UnsafeListener"/>
                  </bpmn:extensionElements>
                  <bpmn:outgoing>flow_start_review</bpmn:outgoing>
                </bpmn:startEvent>
                """
        );

    assertThatThrownBy(() -> validator.validate(xml, "leaveApproval"))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("executionListener");
  }

  @Test
  void rejectsDocumentTypeAndExternalEntityExpansion() {
    String xml = """
        <?xml version="1.0"?>
        <!DOCTYPE definitions [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <process id="safeProcess" isExecutable="true">
            <startEvent id="start"/><endEvent id="end"/>
          </process>
        </definitions>
        """;

    assertThatThrownBy(() -> validator.validate(xml, "safeProcess"))
        .isInstanceOf(WorkflowException.class)
        .hasMessageContaining("格式不正确");
  }

  @Test
  void rejectsEngineObjectAccessAndIndirectMethodCalls() {
    for (String expression : java.util.List.of("${execution.processEngineConfiguration.jdbcPassword}",
        "${task.owner}", "${person['class']}", "${person[methodName]()}")) {
      assertThatThrownBy(() -> validator.validate(WorkflowBpmnTemplates.approval("approval", "Approval")
          .replace("${initiator}", expression), "approval"))
          .isInstanceOf(WorkflowException.class).hasMessageContaining("不允许");
    }
  }

  @Test
  void quotedTextIsNotMistakenForAnExecutableMethodOrEngineObject() {
    validator.validate(WorkflowBpmnTemplates.approval("approval", "Approval")
        .replace("${initiator}", "${reason == 'task' ? 'member1' : 'name()'}"), "approval");
  }

  @Test
  void acceptsFiftyLongCandidateIdsButRejectsOversizedOrInvalidSelections() {
    String candidates = java.util.stream.IntStream.range(0, 50)
        .mapToObj(index -> "user-" + index + "-" + "x".repeat(80)).collect(java.util.stream.Collectors.joining(","));
    String template = WorkflowBpmnTemplates.approval("approval", "Approval")
        .replace("flowable:assignee=\"${initiator}\"", "flowable:candidateUsers=\"%s\"");
    validator.validate(template.formatted(candidates), "approval");
    for (String invalid : java.util.List.of(candidates + ",extra", "user,,other", "x".repeat(101))) {
      assertThatThrownBy(() -> validator.validate(template.formatted(invalid), "approval"))
          .isInstanceOf(WorkflowException.class);
    }
    assertThatThrownBy(() -> validator.validate(WorkflowBpmnTemplates.approval("approval", "Approval")
        .replace("${initiator}", "user1,user2"), "approval")).isInstanceOf(WorkflowException.class);
  }

  @Test
  void fixedRoleAuthoritiesFollowTheRbacContractRatherThanAnAsciiUserIdPattern() {
    String template = WorkflowBpmnTemplates.approval("approval", "Approval")
        .replace("flowable:assignee=\"${initiator}\"", "flowable:candidateGroups=\"%s\"");
    validator.validate(template.formatted("部门/审核,review (operations)"), "approval");
    for (String invalid : java.util.List.of("", "role,,other", "x".repeat(101), "role&#9;control")) {
      assertThatThrownBy(() -> validator.validate(template.formatted(invalid), "approval"))
          .isInstanceOf(WorkflowException.class);
    }
  }
}
