package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.workflow.api.WorkflowModelingException;

class BpmnDiagramPolicyTest {
  private final BpmnDefinitionValidator validator = new BpmnDefinitionValidator();

  @Test
  void supportsDescriptiveShapesAndOneExecutablePoolWithoutImplicitAssignments() {
    validator.validate(
        xml(
            """
        <bpmn:laneSet id="lanes"><bpmn:lane id="lane" name="Finance">
          <bpmn:flowNodeRef>review</bpmn:flowNodeRef>
        </bpmn:lane></bpmn:laneSet>
        <bpmn:dataObject id="data" isCollection="true"/>
        <bpmn:dataObjectReference id="data_ref" dataObjectRef="data"/>
        <bpmn:dataStoreReference id="store_ref"/>
        <bpmn:group id="group" categoryValueRef="category_value"/>
        <bpmn:textAnnotation id="note"><bpmn:text>Text &amp; ${notExecuted}</bpmn:text></bpmn:textAnnotation>
        """,
            """
        <bpmn:collaboration id="collab"><bpmn:participant id="pool" processRef="diagramReview"/></bpmn:collaboration>
        <bpmn:category id="category"><bpmn:categoryValue id="category_value" value="Finance"/></bpmn:category>
        """),
        "diagramReview");
  }

  @Test
  void multipleOrExternalParticipantsAreRejectedEvenWhenXmlWasImported() {
    rejects(
        xml(
            "",
            """
            <bpmn:collaboration id="collab">
              <bpmn:participant id="first" processRef="diagramReview"/>
              <bpmn:participant id="pool" processRef="diagramReview"/>
            </bpmn:collaboration>
            """),
        "diagramParticipant",
        "pool");
    for (String reference : List.of("", "external", "review")) {
      rejects(
          xml(
              "",
              "<bpmn:collaboration id=\"collab\"><bpmn:participant id=\"pool\" processRef=\""
                  + reference
                  + "\"/></bpmn:collaboration>"),
          "diagramParticipant",
          "pool");
    }
  }

  @Test
  void participantMultiplicityAndCrossPoolMessagesCannotPretendToExecute() {
    rejects(
        xml(
            "",
            """
            <bpmn:collaboration id="collab"><bpmn:participant id="pool" processRef="diagramReview">
              <bpmn:participantMultiplicity/>
            </bpmn:participant></bpmn:collaboration>
            """),
        "diagramCollaboration",
        "pool");
    rejects(
        xml(
            "",
            "<bpmn:collaboration id=\"collab\"><bpmn:messageFlow"
                + " id=\"message\"/></bpmn:collaboration>"),
        "diagramCollaboration",
        "message");
  }

  @Test
  void dataAndGroupReferencesMustExistAndHaveTheCorrectType() {
    for (String element :
        List.of(
            "<bpmn:group id=\"bad\" categoryValueRef=\"review\"/>",
            "<bpmn:dataObjectReference id=\"bad\"/>",
            "<bpmn:dataObjectReference id=\"bad\" dataObjectRef=\"unknown\"/>",
            "<bpmn:dataStoreReference id=\"bad\" dataStoreRef=\"review\"/>")) {
      rejects(xml(element, ""), "diagramReference", "bad");
    }
  }

  @Test
  void dataObjectReferencesCannotLeakAcrossNestedProcessScopes() {
    rejects(
        xml(
            """
        <bpmn:dataObject id="data"/>
        <bpmn:subProcess id="sub"><bpmn:dataObjectReference id="bad" dataObjectRef="data"/></bpmn:subProcess>
        """,
            ""),
        "diagramReference",
        "bad");
  }

  @Test
  void laneMembershipMustBeAnActivityInTheSameScope() {
    for (String reference : List.of("missing", "data", "nested")) {
      rejects(
          xml(
              """
        <bpmn:laneSet id="lanes"><bpmn:lane id="lane"><bpmn:flowNodeRef>%s</bpmn:flowNodeRef></bpmn:lane></bpmn:laneSet>
        <bpmn:dataObject id="data"/>
        <bpmn:subProcess id="sub"><bpmn:userTask id="nested" flowable:assignee="actor"/></bpmn:subProcess>
          """
                    .formatted(reference),
              ""),
          "diagramLane",
          "lane");
    }
  }

  @Test
  void siblingLanesCannotClaimTheSameActivityButNestedLanesCanRefineIt() {
    rejects(
        xml(
            """
            <bpmn:laneSet id="lanes">
              <bpmn:lane id="first"><bpmn:flowNodeRef>review</bpmn:flowNodeRef></bpmn:lane>
              <bpmn:lane id="lane"><bpmn:flowNodeRef>review</bpmn:flowNodeRef></bpmn:lane>
            </bpmn:laneSet>
            """,
            ""),
        "diagramLane",
        "lane");
    validator.validate(
        xml(
            """
        <bpmn:laneSet id="lanes"><bpmn:lane id="parent"><bpmn:flowNodeRef>review</bpmn:flowNodeRef>
          <bpmn:childLaneSet id="children"><bpmn:lane id="child"><bpmn:flowNodeRef>review</bpmn:flowNodeRef></bpmn:lane></bpmn:childLaneSet>
        </bpmn:lane></bpmn:laneSet>
        """,
            ""),
        "diagramReview");
  }

  @Test
  void plainTextIsPreservedAndUnsupportedFormatsAreActionable() {
    validator.validate(
        xml(
            "<bpmn:textAnnotation id=\"note\" textFormat=\"text/plain\"><bpmn:text> &lt;script&gt;"
                + " </bpmn:text></bpmn:textAnnotation>",
            ""),
        "diagramReview");
    for (String format : List.of("text/html", "application/xml", "")) {
      rejects(
          xml("<bpmn:textAnnotation id=\"note\" textFormat=\"" + format + "\"/>", ""),
          "diagramTextFormat",
          "note");
    }
  }

  @Test
  void compilationCannotHideAnIdCollisionWithAnExecutableNode() {
    rejects(xml("<bpmn:dataObject id=\"review\"/>", ""), "diagramDuplicateId", "review");
  }

  @Test
  void defaultNamespaceAndCompilerSourcePreservationRemainCompatible() {
    String source =
        xml("<bpmn:group id=\"group\"/>", "").replace("xmlns:bpmn=", "xmlns=").replace("bpmn:", "");
    validator.validate(source, "diagramReview");
    assertThat(BpmnExecutionCompiler.compile(source).xml()).isEqualTo(source);
  }

  private void rejects(String source, String rule, String elementId) {
    assertThatThrownBy(() -> validator.validate(source, "diagramReview"))
        .isInstanceOfSatisfying(
            WorkflowModelingException.class,
            error -> {
              assertThat(error.getMessageKey()).isEqualTo("workflow.modeling." + rule);
              assertThat(error.getElementId()).isEqualTo(elementId);
            });
  }

  static String xml(String inside, String outside) {
    return """
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
            xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://simplepoint.org/workflow">
          <bpmn:process id="diagramReview" isExecutable="true">
            <bpmn:startEvent id="start"/>
            <bpmn:sequenceFlow id="enter" sourceRef="start" targetRef="review"/>
            <bpmn:userTask id="review" flowable:assignee="${initiator}"/>
            <bpmn:sequenceFlow id="exit" sourceRef="review" targetRef="end"/>
            <bpmn:endEvent id="end"/>
            %s
          </bpmn:process>
          %s
        </bpmn:definitions>
        """
        .formatted(inside, outside);
  }
}
