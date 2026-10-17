/** Versioned BPMN business configuration. Approval groups are tenant RBAC authorities. */
const xml = text => String(text).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
export function officeWorkflow(form, formId, version) {
  const expense = form.code === 'office_expense_claim';
  const nodes = [{id:'start',type:'startEvent',name:'提交申请',x:100,y:160,w:36,h:36}];
  const edges = [];
  const task = (id, name, group, x, y=138) => nodes.push({id,type:'userTask',name,group,x,y,w:120,h:80});
  const flow = (id, source, target, condition) => edges.push({id,source,target,condition});
  if (expense) {
    task('manager_review','部门负责人审核','manager',210);
    nodes.push({id:'amount_gate',type:'exclusiveGateway',name:'是否超过 5,000 元',x:390,y:153,w:50,h:50,default:'standard'});
    task('director_review','分管负责人审批','director',500,30);
    task('finance_review','财务复核','finance',680);
    nodes.push({id:'end',type:'endEvent',name:'审核完成',x:870,y:160,w:36,h:36});
    flow('submit','start','manager_review');flow('amount_check','manager_review','amount_gate');
    flow('large','amount_gate','director_review','${amount > 5000}');flow('standard','amount_gate','finance_review');
    flow('director_approved','director_review','finance_review');flow('complete','finance_review','end');
  } else {
    const meeting=form.code==='office_meeting_room';
    task('review',meeting?'行政核实场地及会务安排':'部门负责人确认日报',meeting?'administration':'manager',230);
    nodes.push({id:'end',type:'endEvent',name:'审核完成',x:440,y:160,w:36,h:36});
    flow('submit','start','review');flow('complete','review','end');
  }
  const processId=form.code+'_approval';
  const nodeXml=nodes.map(n=>`<bpmn:${n.type} id="${n.id}" name="${xml(n.name)}"${n.group?` flowable:candidateGroups="ROLE_Office_${n.group}"`:''}${n.default?` default="${n.default}"`:''}>`
    +edges.filter(e=>e.target===n.id).map(e=>`<bpmn:incoming>${e.id}</bpmn:incoming>`).join('')
    +edges.filter(e=>e.source===n.id).map(e=>`<bpmn:outgoing>${e.id}</bpmn:outgoing>`).join('')+`</bpmn:${n.type}>`).join('\n');
  const edgeXml=edges.map(e=>`<bpmn:sequenceFlow id="${e.id}" sourceRef="${e.source}" targetRef="${e.target}">${e.condition?`<bpmn:conditionExpression xsi:type="bpmn:tFormalExpression"><![CDATA[${e.condition}]]></bpmn:conditionExpression>`:''}</bpmn:sequenceFlow>`).join('\n');
  const shapes=nodes.map(n=>`<bpmndi:BPMNShape id="${n.id}_di" bpmnElement="${n.id}"><dc:Bounds x="${n.x}" y="${n.y}" width="${n.w}" height="${n.h}"/></bpmndi:BPMNShape>`).join('\n');
  const lines=edges.map(e=>{const a=nodes.find(n=>n.id===e.source),b=nodes.find(n=>n.id===e.target);return `<bpmndi:BPMNEdge id="${e.id}_di" bpmnElement="${e.id}"><di:waypoint x="${a.x+a.w}" y="${a.y+a.h/2}"/><di:waypoint x="${b.x}" y="${b.y+b.h/2}"/></bpmndi:BPMNEdge>`;}).join('\n');
  return {key:processId,name:form.name+'审批',description:form.description,category:'人力办公',
    bpmnXml:`<?xml version="1.0" encoding="UTF-8"?><bpmn:definitions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" xmlns:flowable="http://flowable.org/bpmn" id="Definitions_${processId}" targetNamespace="https://simplepoint.org/workflow"><bpmn:process id="${processId}" name="${xml(form.name)}审批" isExecutable="true">${nodeXml}\n${edgeXml}</bpmn:process><bpmndi:BPMNDiagram id="Diagram_${processId}"><bpmndi:BPMNPlane id="Plane_${processId}" bpmnElement="${processId}">${shapes}\n${lines}</bpmndi:BPMNPlane></bpmndi:BPMNDiagram></bpmn:definitions>`,
    formContract:{schemaVersion:1,variables:expense?[{name:'amount',label:'报销总额',type:'decimal',scope:'start',required:true,defaultValue:null}]:[],
      startBinding:{provider:'simplepoint-app',formId,version,sceneId:'main',variableMappings:expense?{total_amount:'amount'}:{}},taskBindings:{}},
  };
}
