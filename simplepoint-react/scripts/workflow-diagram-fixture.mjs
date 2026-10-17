/** Real BPMN semantics and DI; production modeler is not replaced by a fixture implementation. */
export function workflowDiagramXml(base, {textFormat = 'text/plain', participantMultiplicity = false} = {}) {
  return base.replace('<bpmn:startEvent', `<bpmn:laneSet id="lanes"><bpmn:lane id="lane" name="职责泳道">
    <bpmn:flowNodeRef>start</bpmn:flowNodeRef><bpmn:flowNodeRef>review</bpmn:flowNodeRef><bpmn:flowNodeRef>end</bpmn:flowNodeRef>
    </bpmn:lane></bpmn:laneSet><bpmn:startEvent`)
    .replace('</bpmn:process>', `<bpmn:dataObject id="data"/>
      <bpmn:dataObjectReference id="data_ref" name="申请材料" dataObjectRef="data"/>
      <bpmn:dataStoreReference id="store_ref" name="业务归档"/>
      <bpmn:textAnnotation id="note" textFormat="${textFormat}"><bpmn:text>原始说明</bpmn:text></bpmn:textAnnotation>
      <bpmn:group id="group"/>
      <bpmn:association id="note_link" sourceRef="review" targetRef="note"/>
      </bpmn:process>
      <bpmn:collaboration id="collab"><bpmn:participant id="pool" name="审批泳池" processRef="approval">${participantMultiplicity ? '<bpmn:participantMultiplicity/>' : ''}</bpmn:participant></bpmn:collaboration>`)
    .replace('bpmnElement="approval"', 'bpmnElement="collab"')
    .replace('<bpmndi:BPMNShape id="start_di"', `<bpmndi:BPMNShape id="pool_di" bpmnElement="pool" isHorizontal="true"><dc:Bounds x="60" y="110" width="570" height="410"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="lane_di" bpmnElement="lane" isHorizontal="true"><dc:Bounds x="90" y="110" width="540" height="410"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="start_di"`)
    .replace('</bpmndi:BPMNPlane>', `<bpmndi:BPMNShape id="data_di" bpmnElement="data_ref"><dc:Bounds x="150" y="330" width="36" height="50"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="store_di" bpmnElement="store_ref"><dc:Bounds x="270" y="335" width="50" height="50"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="note_di" bpmnElement="note"><dc:Bounds x="430" y="235" width="150" height="70"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="group_di" bpmnElement="group"><dc:Bounds x="410" y="350" width="160" height="110"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="note_link_di" bpmnElement="note_link"><di:waypoint x="320" y="198"/><di:waypoint x="430" y="260"/></bpmndi:BPMNEdge>
      </bpmndi:BPMNPlane>`);
}
