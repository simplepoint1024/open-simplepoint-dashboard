package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BpmnDiagramCompilationTest {
  @Test
  void executionProjectionRemovesOnlyDataGraphicsAndTheirAssociations() {
    String source =
        BpmnDiagramPolicyTest.xml(
                """
        <bpmn:dataObject id="data" name="initiator"/>
        <bpmn:dataObjectReference id="data_ref" dataObjectRef="data"/>
        <bpmn:dataStoreReference id="store_ref"/>
        <bpmn:textAnnotation id="note"><bpmn:text>${untouched}</bpmn:text></bpmn:textAnnotation>
        <bpmn:association id="data_link" sourceRef="data_ref" targetRef="review"/>
        <bpmn:association id="note_link" sourceRef="note" targetRef="review"/>
        """,
                """
        <bpmndi:BPMNDiagram xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" id="diagram">
          <bpmndi:BPMNPlane id="plane" bpmnElement="diagramReview">
            <bpmndi:BPMNShape id="data_shape" bpmnElement="data_ref"/>
            <bpmndi:BPMNShape id="task_shape" bpmnElement="review"/>
            <bpmndi:BPMNEdge id="data_edge" bpmnElement="data_link"/>
            <bpmndi:BPMNEdge id="task_edge" bpmnElement="enter"/>
          </bpmndi:BPMNPlane>
        </bpmndi:BPMNDiagram>
        """)
            .replace(
                "<bpmn:userTask id=\"review\" flowable:assignee=\"${initiator}\"/>",
                """
                <bpmn:userTask id="review" flowable:assignee="${initiator}">
                  <bpmn:dataInputAssociation id="read_data">
                <bpmn:sourceRef>data_ref</bpmn:sourceRef>
                <bpmn:targetRef>input</bpmn:targetRef>
                </bpmn:dataInputAssociation>
                </bpmn:userTask>
                """);
    var compiled = BpmnExecutionCompiler.compile(source);
    assertThat(compiled.diagramData()).isTrue();
    assertThat(compiled.xml())
        .doesNotContain(
            "dataObject",
            "dataStoreReference",
            "data_link",
            "data_shape",
            "data_edge",
            "read_data");
    assertThat(compiled.xml())
        .contains(
            "task_shape",
            "task_edge",
            "note_link",
            "${untouched}",
            "${initiator}",
            "id=\"review\"");
    assertThat(compiled.manifest())
        .contains("diagramData=documentation-v1", "sourceSha256=", "executionSha256=");
    assertThat(BpmnExecutionCompiler.compile(source)).isEqualTo(compiled);
    assertThat(BpmnDiagramCompilation.owner(BpmnDefinitionValidator.parse(source)))
        .isEqualTo("data_ref");
  }

  @Test
  void compilingDataWithoutIdsDoesNotRemoveTheDiagramOrOtherUnrelatedElements() {
    String source =
        BpmnDiagramPolicyTest.xml(
            "<bpmn:dataObject/>",
            """
            <bpmndi:BPMNDiagram xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI">
              <bpmndi:BPMNPlane bpmnElement="diagramReview"/>
            </bpmndi:BPMNDiagram>
            """);
    assertThat(BpmnExecutionCompiler.compile(source).xml())
        .contains("BPMNDiagram", "BPMNPlane")
        .doesNotContain("dataObject");
  }

  @Test
  void mixedLoopsAndDataRequireBothDeploymentCapabilities() {
    String source =
        BpmnDiagramPolicyTest.xml("<bpmn:dataStoreReference id=\"store\"/>", "")
            .replace(
                "flowable:assignee=\"${initiator}\"/>",
                """
        flowable:assignee="${initiator}"><bpmn:standardLoopCharacteristics loopMaximum="2"/></bpmn:userTask>
        """);
    var compiled = BpmnExecutionCompiler.compile(source);
    assertThat(compiled.xml())
        .contains("multiInstanceLoopCharacteristics")
        .doesNotContain("standardLoopCharacteristics", "dataStoreReference");
    assertThat(compiled.matchesDeployment(List.of())).isFalse();
    assertThat(compiled.matchesDeployment(List.of(BpmnExecutionCompiler.MANIFEST_RESOURCE)))
        .isFalse();
    assertThat(compiled.matchesDeployment(List.of(BpmnExecutionCompiler.DIAGRAM_RESOURCE)))
        .isFalse();
    assertThat(
            compiled.matchesDeployment(
                List.of(
                    BpmnExecutionCompiler.MANIFEST_RESOURCE,
                    BpmnExecutionCompiler.DIAGRAM_RESOURCE)))
        .isTrue();
  }

  @Test
  void annotationsAndGroupsAloneNeverRequireCompilation() {
    String source =
        BpmnDiagramPolicyTest.xml(
            "<bpmn:group id=\"group\"/><bpmn:textAnnotation id=\"note\"/>", "");
    var compiled = BpmnExecutionCompiler.compile(source);
    assertThat(compiled.xml()).isEqualTo(source);
    assertThat(compiled.diagramData()).isFalse();
    assertThat(compiled.matchesDeployment(List.of())).isTrue();
  }
}
