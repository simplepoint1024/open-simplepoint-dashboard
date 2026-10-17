package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.workflow.api.WorkflowModelingException;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract.Variable;

class BpmnMultiInstancePolicyTest {
  private final BpmnDefinitionValidator validator = new BpmnDefinitionValidator();
  private final WorkflowFormContract contract =
      new WorkflowFormContract(
          1,
          List.of(
              new Variable("reviewers", "Reviewers", "users", "start", true, null),
              new Variable("groups", "Groups", "roles", "start", false, null),
              new Variable("amount", "Amount", "number", "both", false, null)),
          null,
          Map.of());

  @Test
  void acceptsNativeCollectionsCountsAndBooleanCompletionConditions() {
    for (String source : List.of("reviewers", "${reviewers}", "#{ reviewers }", "groups")) {
      validate("flowable:collection=\"" + source + "\" flowable:elementVariable=\"item\"", "");
    }
    for (String count : List.of("1", "50", "+2", "${amount}")) {
      validate("", "<bpmn:loopCardinality>" + count + "</bpmn:loopCardinality>");
    }
    validate(
        "flowable:collection=\"reviewers\"",
        """
        <bpmn:completionCondition>
          ${nrOfCompletedInstances / nrOfInstances >= 0.5}
        </bpmn:completionCondition>
        """);
  }

  @Test
  void requiresExactlyOneSourceAndBoundsFixedCounts() {
    rejects("", "", "multiSource");
    rejects(
        "flowable:collection=\"reviewers\"",
        "<bpmn:loopCardinality>2</bpmn:loopCardinality>",
        "multiSource");
    for (String count : List.of("0", "51", "-1", "1.5", "NaN", "99999999999999999")) {
      rejects("", "<bpmn:loopCardinality>" + count + "</bpmn:loopCardinality>", "multiCount");
    }
    rejects("", "<bpmn:loopCardinality>2</bpmn:loopCardinality>".repeat(2), "multiSource");
  }

  @Test
  void validatesDeclaredCollectionKindsWithoutDisablingLegacyEmptyContracts() {
    for (String source : List.of("amount", "unknown", "broken name")) {
      rejects("flowable:collection=\"" + source + "\"", "", "multiCollection");
    }
    validator.validate(
        xml("flowable:collection=\"legacyUsers\"", ""),
        "multiReview",
        WorkflowFormContract.empty());
  }

  @Test
  void rejectsReservedOverlappingAndCollidingIterationNames() {
    for (String item :
        List.of("initiator", "loopCounter", "nrOfInstances", "reviewers", "amount", "a-b")) {
      rejects(
          "flowable:collection=\"reviewers\" flowable:elementVariable=\"" + item + "\"",
          "",
          "multiItem");
    }
    rejects(
        "flowable:collection=\"reviewers\" flowable:elementVariable=\"item\""
            + " flowable:elementIndexVariable=\"item\"",
        "",
        "multiItem");
    rejects(
        "flowable:elementVariable=\"item\"",
        "<bpmn:loopCardinality>2</bpmn:loopCardinality>",
        "multiItem");
    String nested =
        xml("flowable:collection=\"reviewers\" flowable:elementVariable=\"item\"", "")
            .replace(
                "<bpmn:userTask",
                """
                <bpmn:subProcess id="outer">
                  <bpmn:multiInstanceLoopCharacteristics flowable:collection="reviewers"
                      flowable:elementVariable="item"/>
                  <bpmn:userTask
                """)
            .replace("</bpmn:userTask>", "</bpmn:userTask></bpmn:subProcess>");
    assertIssue(nested, "multiItem");
  }

  @Test
  void rejectsUnsafeOrMalformedExpressionsAndNonBooleanCompletionLiterals() {
    for (String expression :
        List.of(
            "${execution.variables}", "${users.fetch()}", "${users['class']}", "${users == }")) {
      rejects("flowable:collection=\"" + expression + "\"", "", "multiExpression");
    }
    for (String expression : List.of("yes", "${1}", "${null}", "${'true'}")) {
      rejects(
          "flowable:collection=\"reviewers\"",
          "<bpmn:completionCondition>" + expression + "</bpmn:completionCondition>",
          "multiCompletion");
    }
  }

  @Test
  void validatesOwnerAndNativeModeWithoutSilentlyNormalizingXml() {
    assertIssue(
        xml("", "<bpmn:loopCardinality>2</bpmn:loopCardinality>")
            .replace("userTask", "exclusiveGateway"),
        "multiOwner");
    assertIssue(
        xml("", "<bpmn:loopCardinality>2</bpmn:loopCardinality>")
            .replace("isSequential=\"false\"", "isSequential=\"wrong\""),
        "multiMode");
  }

  @Test
  void removingOrRenamingAnIterationVariableCannotLeaveDanglingAssignments() {
    String configured =
        xml("flowable:collection=\"reviewers\" flowable:elementVariable=\"reviewer\"", "")
            .replace("flowable:assignee=\"actor\"", "flowable:assignee=\"${reviewer}\"");
    validator.validate(configured, "multiReview", contract);
    assertIssue(
        configured.replace(
            "flowable:elementVariable=\"reviewer\"", "flowable:elementVariable=\"renamed\""),
        "assignmentVariable");
    assertIssue(
        configured.replaceAll(
            "<bpmn:multiInstanceLoopCharacteristics[^>]*>.*?</bpmn:multiInstanceLoopCharacteristics>",
            ""),
        "assignmentVariable");
  }

  private void validate(String attributes, String children) {
    validator.validate(xml(attributes, children), "multiReview", contract);
  }

  private void rejects(String attributes, String children, String rule) {
    assertIssue(xml(attributes, children), rule);
  }

  private void assertIssue(String xml, String rule) {
    assertThatThrownBy(() -> validator.validate(xml, "multiReview", contract))
        .isInstanceOfSatisfying(
            WorkflowModelingException.class,
            error -> {
              assertThat(error.getMessageKey()).isEqualTo("workflow.modeling." + rule);
              assertThat(error.getElementId()).isEqualTo("review");
            });
  }

  private String xml(String attributes, String children) {
    return """
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
    xmlns:flowable="http://flowable.org/bpmn">
  <bpmn:process id="multiReview" isExecutable="true">
    <bpmn:startEvent id="start"/>
    <bpmn:userTask id="review" flowable:assignee="actor">
      <bpmn:multiInstanceLoopCharacteristics isSequential="false" %s>%s</bpmn:multiInstanceLoopCharacteristics>
    </bpmn:userTask>
    <bpmn:endEvent id="end"/>
  </bpmn:process>
</bpmn:definitions>
"""
        .formatted(attributes, children);
  }
}
