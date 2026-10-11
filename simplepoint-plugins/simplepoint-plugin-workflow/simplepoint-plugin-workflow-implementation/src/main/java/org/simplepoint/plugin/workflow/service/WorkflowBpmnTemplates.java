package org.simplepoint.plugin.workflow.service;

/** Creates safe, immediately executable starter BPMN documents. */
final class WorkflowBpmnTemplates {

  private WorkflowBpmnTemplates() {}

  static String approval(final String key, final String name) {
    String safeKey = xml(key);
    String safeName = xml(name);
    String template =
        """
<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  xmlns:flowable="http://flowable.org/bpmn"
  id="Definitions_%s" targetNamespace="https://simplepoint.org/workflow">
  <bpmn:process id="%s" name="%s" isExecutable="true">
    <bpmn:startEvent id="start" name="开始"><bpmn:outgoing>flow_start_review</bpmn:outgoing></bpmn:startEvent>
    <bpmn:userTask id="review" name="审批" flowable:assignee="${initiator}">
      <bpmn:incoming>flow_start_review</bpmn:incoming><bpmn:outgoing>flow_review_end</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:endEvent id="end" name="结束"><bpmn:incoming>flow_review_end</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="flow_start_review" sourceRef="start" targetRef="review"/>
    <bpmn:sequenceFlow id="flow_review_end" sourceRef="review" targetRef="end"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="Diagram_%s"><bpmndi:BPMNPlane id="Plane_%s" bpmnElement="%s">
    <bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="160" y="170" width="36" height="36"/></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="review_di" bpmnElement="review"><dc:Bounds x="260" y="148" width="100" height="80"/></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="end_di" bpmnElement="end"><dc:Bounds x="430" y="170" width="36" height="36"/></bpmndi:BPMNShape>
    <bpmndi:BPMNEdge id="flow_start_review_di" bpmnElement="flow_start_review">
      <di:waypoint x="196" y="188"/><di:waypoint x="260" y="188"/>
    </bpmndi:BPMNEdge>
    <bpmndi:BPMNEdge id="flow_review_end_di" bpmnElement="flow_review_end">
      <di:waypoint x="360" y="188"/><di:waypoint x="430" y="188"/>
    </bpmndi:BPMNEdge>
  </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
</bpmn:definitions>
        """;
    // BPMN IDs are document-wide, including shapes and edges. A legal process key must not
    // collide with a starter node. Rewrite the static template before interpolating user text,
    // preserving ordinary starter IDs and never changing names or a process reference.
    if (java.util.Set.of(
            "start",
            "review",
            "end",
            "flow_start_review",
            "flow_review_end",
            "start_di",
            "review_di",
            "end_di",
            "flow_start_review_di",
            "flow_review_end_di")
        .contains(key)) {
      template =
          template
              .replace("\"" + key + "\"", "\"node_" + key + "\"")
              .replace(">" + key + "<", ">node_" + key + "<");
    }
    return template.formatted(safeKey, safeKey, safeName, safeKey, safeKey, safeKey);
  }

  private static String xml(final String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }
}
