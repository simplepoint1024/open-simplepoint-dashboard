import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import flowable from '../../../simplepoint-react/libs/components/WorkflowDesigner/flowableModdle.ts';
const frontend = createRequire(new URL('../../../simplepoint-react/package.json', import.meta.url));
const {BpmnModdle} = createRequire(frontend.resolve('bpmn-js/package.json'))('bpmn-moddle');

/** Adds deterministic editable DI to our bounded acyclic acceptance configurations, preserving semantics. */
export async function withAcceptanceDiagram(xml) {
  const moddle = new BpmnModdle({flowable});
  const {rootElement: definitions, warnings} = await moddle.fromXML(xml);
  assert.deepEqual(warnings, []);
  if (definitions.diagrams?.length) return xml.trim();
  const processes = definitions.rootElements.filter(element => element.$type === 'bpmn:Process');
  assert.equal(processes.length, 1);
  const process = processes[0], collaboration = definitions.rootElements.find(element => element.$type === 'bpmn:Collaboration');
  const plane = moddle.create('bpmndi:BPMNPlane', {id: process.id + '_plane', bpmnElement: collaboration ?? process, planeElement: []});
  const shapes = new Map();
  const shape = (element, bounds, extra = {}) => {
    const result = moddle.create('bpmndi:BPMNShape', {id: element.id + '_di', bpmnElement: element,
      bounds: moddle.create('dc:Bounds', bounds), ...extra});
    plane.planeElement.push(result); shapes.set(element.id, result); return result;
  };
  const line = element => {
    const a = shapes.get(element.sourceRef?.id)?.bounds, b = shapes.get(element.targetRef?.id)?.bounds;
    if (!a || !b) return;
    const middle = (a.x + a.width + b.x) / 2;
    const points = [{x: a.x + a.width, y: a.y + a.height / 2}, {x: middle, y: a.y + a.height / 2},
      {x: middle, y: b.y + b.height / 2}, {x: b.x, y: b.y + b.height / 2}];
    plane.planeElement.push(moddle.create('bpmndi:BPMNEdge', {id: element.id + '_di', bpmnElement: element,
      waypoint: points.map(point => moddle.create('dc:Point', point))}));
  };
  const measure = container => {
    const nodes = (container.flowElements ?? []).filter(element => element.$instanceOf('bpmn:FlowNode'));
    const flows = (container.flowElements ?? []).filter(element => element.$type === 'bpmn:SequenceFlow');
    assert(nodes.length > 0 && nodes.length <= 100);
    const remaining = new Set(nodes), levels = new Map(), columns = [];
    while (remaining.size) {
      const eligible = [...remaining].filter(node => flows.filter(flow => flow.targetRef === node).every(flow => levels.has(flow.sourceRef)));
      assert(eligible.length, 'Acceptance diagram generator requires acyclic sequence flows');
      for (const node of eligible) {
        const level = Math.max(-1, ...flows.filter(flow => flow.targetRef === node).map(flow => levels.get(flow.sourceRef))) + 1;
        levels.set(node, level); (columns[level] ??= []).push(node); remaining.delete(node);
      }
    }
    const sizes = new Map(nodes.map(node => [node, node.$type === 'bpmn:SubProcess' ? measure(node) : {
      width: node.$instanceOf('bpmn:Event') ? 36 : node.$instanceOf('bpmn:Gateway') ? 50 : 150,
      height: node.$instanceOf('bpmn:Event') ? 36 : node.$instanceOf('bpmn:Gateway') ? 50 : 90,
    }]));
    const height = Math.max(...columns.map(column => column.reduce((sum, node) => sum + sizes.get(node).height + 80, 0))) + 80;
    let x = 50; const placements = [];
    for (const column of columns) {
      const width = Math.max(...column.map(node => sizes.get(node).width));
      const total = column.reduce((sum, node) => sum + sizes.get(node).height + 80, -80);
      let y = (height - total) / 2;
      for (const node of column) {const size = sizes.get(node); placements.push({node, x, y, size}); y += size.height + 80;}
      x += width + 100;
    }
    return {width: x - 50, height, placements, flows};
  };
  const place = (layout, offsetX, offsetY) => {
    for (const {node, x, y, size} of layout.placements) {
      shape(node, {x: offsetX + x, y: offsetY + y, width: size.width, height: size.height},
        node.$type === 'bpmn:SubProcess' ? {isExpanded: true} : {});
      if (size.placements) place(size, offsetX + x, offsetY + y);
    }
    layout.flows.forEach(line);
  };
  const layout = measure(process), offsetX = collaboration ? 90 : 30, offsetY = 60;
  const participant = collaboration?.participants?.find(item => item.processRef === process);
  if (collaboration) assert(participant && collaboration.participants.length === 1);
  if (participant) shape(participant, {x: 30, y: 20, width: layout.width + 150, height: layout.height + 260}, {isHorizontal: true});
  for (const laneSet of process.laneSets ?? []) for (const lane of laneSet.lanes ?? []) {
    shape(lane, {x: 60, y: 20, width: layout.width + 120, height: layout.height + 260}, {isHorizontal: true});
  }
  place(layout, offsetX, offsetY);
  let artifactX = offsetX + 50;
  for (const artifact of [...(process.flowElements ?? []), ...(process.artifacts ?? [])]) {
    if (!['bpmn:DataObjectReference', 'bpmn:DataStoreReference', 'bpmn:TextAnnotation', 'bpmn:Group'].includes(artifact.$type)) continue;
    const width = artifact.$type === 'bpmn:TextAnnotation' ? 260 : 100;
    shape(artifact, {x: artifactX, y: layout.height + 80, width, height: 90}); artifactX += width + 45;
  }
  (process.artifacts ?? []).filter(element => element.$type === 'bpmn:Association').forEach(line);
  definitions.diagrams = [moddle.create('bpmndi:BPMNDiagram', {id: process.id + '_diagram', plane})];
  const result = (await moddle.toXML(definitions, {format: true})).xml;
  const verified = await moddle.fromXML(result); assert.deepEqual(verified.warnings, []);
  return result.trim();
}
