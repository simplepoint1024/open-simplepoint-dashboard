import {readFileSync} from 'node:fs';
import {createWorkflowPersonalFixture, definitionFields} from './workflow-personal-fixture.mjs';
import {fixtureSchema, fixturePage} from './query-fixture.mjs';
const numericLiterals = JSON.parse(readFileSync(new URL('../libs/components/ExpressionEditor/numericLiterals.fixture.json', import.meta.url), 'utf8'));
const priorityLiterals = JSON.parse(readFileSync(new URL('../libs/components/ExpressionEditor/priorityLiterals.fixture.json', import.meta.url), 'utf8'));

export const workflowRoutes = ['Definitions', 'Tasks', 'Instances'].map((expose, index) => ({
  id: `workflow-${expose}`, code: 'workflow.view', type: 'PAGE', routeKind: 'item',
  path: `/smoke/workflow-${expose.toLowerCase()}`, component: `workflow/${expose}`,
  label: ['流程定义测试', '流程任务测试', '流程事项测试'][index], scopeTypes: ['PLATFORM', 'TENANT'],
}));

const xml = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
 xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
 xmlns:di="http://www.omg.org/spec/DD/20100524/DI" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://simplepoint.org/workflow">
 <bpmn:process id="approval" name="审批流程" isExecutable="true">
  <bpmn:startEvent id="start" name="开始"><bpmn:outgoing>flow1</bpmn:outgoing></bpmn:startEvent>
  <bpmn:userTask id="review" name="审批" flowable:assignee="\${approver}" flowable:candidateGroups="tenant.approver" flowable:dueDate="P1DT2H">
   <bpmn:incoming>flow1</bpmn:incoming><bpmn:outgoing>flow2</bpmn:outgoing>
  </bpmn:userTask>
  <bpmn:endEvent id="end" name="结束"><bpmn:incoming>flow2</bpmn:incoming></bpmn:endEvent>
  <bpmn:sequenceFlow id="flow1" sourceRef="start" targetRef="review"><bpmn:conditionExpression>\${reviewRole == "tenant.approver"}</bpmn:conditionExpression></bpmn:sequenceFlow>
  <bpmn:sequenceFlow id="flow2" sourceRef="review" targetRef="end"><bpmn:conditionExpression>\${approver == "member-1"}</bpmn:conditionExpression></bpmn:sequenceFlow>
 </bpmn:process>
 <bpmndi:BPMNDiagram id="diagram"><bpmndi:BPMNPlane id="plane" bpmnElement="approval">
  <bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="120" y="180" width="36" height="36"/></bpmndi:BPMNShape>
  <bpmndi:BPMNShape id="review_di" bpmnElement="review"><dc:Bounds x="220" y="158" width="100" height="80"/></bpmndi:BPMNShape>
  <bpmndi:BPMNShape id="end_di" bpmnElement="end"><dc:Bounds x="380" y="180" width="36" height="36"/></bpmndi:BPMNShape>
  <bpmndi:BPMNEdge id="flow1_di" bpmnElement="flow1"><di:waypoint x="156" y="198"/><di:waypoint x="220" y="198"/></bpmndi:BPMNEdge>
  <bpmndi:BPMNEdge id="flow2_di" bpmnElement="flow2"><di:waypoint x="320" y="198"/><di:waypoint x="380" y="198"/></bpmndi:BPMNEdge>
 </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
</bpmn:definitions>`;

export function createWorkflowFixture() {
  const variables = [{name: 'approver', label: '指定审批人', type: 'user', scope: 'start', required: true},
    {name: 'approved', label: '是否通过', type: 'boolean', scope: 'task', required: true, defaultValue: false},
    {name: 'reviewRole', label: '审批角色变量', type: 'role', scope: 'both', required: false}];
  const contract = {schemaVersion: 1, variables, taskBindings: {}};
  const permissions = {canCreate: true, canEdit: true, canPublish: true, canCopyVersion: true, canStart: true};
  const definition = {id: 'definition1', key: 'approval', name: '审批流程', latestVersion: 2, draftRevision: 1,
    status: 'PUBLISHED', unpublishedChanges: false, createdBy: 'actor', updatedAt: '2026-10-09T00:00:00Z'};
  const versions = [2, 1].map(version => ({id: `version${version}`, version, checksum: `checksum${version}`,
    publishedBy: 'actor', publishedAt: '2026-10-09T00:00:00Z', comment: `发布说明 ${version}`}));
  let draftXml = xml, draftContract = structuredClone(contract), failCopy = false;
  const users = Array.from({length: 125}, (_, index) => ({id: `member-${index}`, value: `member-${index}`, name: `租户成员 ${index}`}));
  const roles = [{id: 'role1', value: 'tenant.approver', name: '审批角色'}];
  const writes = [], requests = [];
  const numericPreviewCompletions = [], numericPreviewDelays = new Map();
  let failNumericPreview;
  const priorityPreviewCompletions = [], priorityPreviewDelays = new Map();
  let failPriorityPreview;
  const instance = {id: 'instance1', definitionId: 'definition1', definitionKey: 'approval', definitionName: '审批流程',
    definitionVersion: 1, title: '申请事项', status: 'RUNNING', startedAt: '2026-10-09T00:00:00Z', starterId: 'actor'};
  const detail = () => ({definition: {...definition}, bpmnXml: draftXml, formContract: structuredClone(draftContract), versions, permissions});
  const personal = createWorkflowPersonalFixture({xml, contract, definition});
  return {writes, requests, personal, numericPreviewCompletions, priorityPreviewCompletions, snapshot: () => structuredClone({definition, versions, instance, draftContract}),
    delayPriorityPreview(value, milliseconds) {priorityPreviewDelays.set(value, milliseconds);},
    failPriorityPreview(value) {failPriorityPreview = value;},
    delayNumericPreview(expression, milliseconds) {numericPreviewDelays.set(expression, milliseconds);},
    failNumericPreview(expression) {failNumericPreview = expression;},
    expressionRoles(values) {roles.push(...structuredClone(values));},
    expressionUsers(values) {users.push(...structuredClone(values));},
    expressionConditions(values, variables = []) {
      draftContract.variables.push({name: 'amount', label: '数值', type: 'number', scope: 'both', required: false});
      draftContract.variables.push(...structuredClone(variables));
      const escape = value => value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('\r', '&#13;');
      for (const [id, value] of Object.entries(values)) {
        if (!['flow1', 'flow2'].includes(id)) throw Error('Unsupported fixture flow');
        draftXml = draftXml.replace(new RegExp(`(<bpmn:sequenceFlow id="${id}"[^>]*><bpmn:conditionExpression>)[\\s\\S]*?(</bpmn:conditionExpression>)`),
          (_, opening, closing) => opening + escape(value) + closing);
      }
    },
    expressionValues(values) {
      const escape = value => value.replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
      draftXml = xml.replace(/<bpmn:userTask\b[^>]*>/, opening => {
        for (const [field, value] of Object.entries(values)) {
          if (!['assignee', 'candidateUsers', 'candidateGroups', 'dueDate', 'priority'].includes(field)) throw Error('Unsupported fixture property');
          opening = opening.replace(new RegExp(` flowable:${field}="[^"]*"`), '');
          if (value != null) opening = opening.replace(/>$/, ` flowable:${field}="${escape(value)}">`);
        }
        return opening;
      });
    },
    bindStart() {draftContract = {...draftContract, startBinding: {provider: 'legacy.provider', formId: 'stored-form', version: 3, variableMappings: {reviewer: 'approver'}}};},
    failCopy() {failCopy = true;}, failComplete() {personal.fail('complete');}, failCancel() {personal.fail('cancel');},
    async handle(req, url, respond, expectedFailure = () => {}) {
      const path = url.pathname;
      if (!path.startsWith('/workflow/') && !path.startsWith('/common/tenant-directory/')) return false;
      if (path.startsWith('/workflow/mf/')) return false;
      requests.push({path, params: Object.fromEntries(url.searchParams), method: req.method});
      let data;
      if (req.method !== 'GET') {
        const chunks = []; for await (const chunk of req) chunks.push(chunk);
        data = JSON.parse(Buffer.concat(chunks).toString() || '{}');
        writes.push({path, data});
      }
      if (await personal.handle(req, url, data, respond, expectedFailure)) return true;
      if (path.endsWith('/copy-to-draft')) {
        if (Number(url.searchParams.get('expectedRevision')) !== definition.draftRevision) throw Error('Copy lost its captured draft revision');
        const version = Number(path.split('/').at(-2));
        if (!versions.some(item => item.version === version)) throw Error('Copy references an unpublished version');
        await new Promise(resolve => setTimeout(resolve, 250));
        if (failCopy) {failCopy = false; expectedFailure(503); return respond({message: '复制历史版本失败（测试）'}, 503), true;}
        Object.assign(definition, {name: '历史名称', draftRevision: definition.draftRevision + 1, unpublishedChanges: true});
        draftXml = xml; draftContract = structuredClone({...contract, variables: version === 1 ? variables
          : [...variables, {name: 'note', label: '补充说明', type: 'string', scope: 'both', required: false}]});
        return respond(detail()), true;
      }
      if (path.startsWith('/common/tenant-directory/')) {
        const items = path.includes('/roles') ? roles : users;
        if (path.endsWith('/selected')) return respond(items.filter(item => url.searchParams.get('values').split(',').includes(item.value))), true;
        const page = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
        if (size > 50) throw Error('Unbounded tenant directory query');
        const rows = items.filter(item => item.name.includes(url.searchParams.get('keyword') ?? ''));
        return respond({content: rows.slice(page * size, (page + 1) * size),
          page: {number: page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}}), true;
      }
      if (path === '/workflow/definitions/permissions') return respond(permissions), true;
      if (path === '/workflow/definitions/priority-preview') {
        const value = url.searchParams.get('value');
        const delay = priorityPreviewDelays.get(value);
        if (delay) await new Promise(resolve => setTimeout(resolve, delay));
        if (failPriorityPreview === value) {
          failPriorityPreview = undefined; expectedFailure(503);
          priorityPreviewCompletions.push({value, failed: true});
          return respond({message: '优先级常量预览失败（隔离测试）'}, 503), true;
        }
        const item = priorityLiterals.find(item => item.source === value);
        if (item) {
          priorityPreviewCompletions.push({value, issue: item.issue});
          return respond({value: item.value ?? null, issue: item.issue ?? null}), true;
        }
        // Canonical 0..100 fixed values are emitted by the genuine priority number control.
        if (/^(?:0|[1-9][0-9]{0,2})$/.test(value ?? '') && Number(value) <= 100) {
          priorityPreviewCompletions.push({value});
          return respond({value, issue: null}), true;
        }
        throw Error('Unverified priority literal fixture: ' + value);
      }
      if (path === '/workflow/definitions/literal-preview') {
        const expression = url.searchParams.get('expression');
        const body = /^\$\{\s*(.*?)\s*\}$/.exec(expression?.trim() ?? '')?.[1];
        const item = numericLiterals.find(item => item.literal === body);
        if (!item) throw Error('Unverified numeric literal fixture: ' + body);
        const delay = numericPreviewDelays.get(expression);
        if (delay) await new Promise(resolve => setTimeout(resolve, delay));
        if (failNumericPreview === expression) {
          failNumericPreview = undefined; expectedFailure(503);
          numericPreviewCompletions.push({expression, failed: true});
          return respond({message: '数字身份预览失败（隔离测试）'}, 503), true;
        }
        numericPreviewCompletions.push({expression, value: item.value});
        return respond({value: item.value}), true;
      }
      if (path === '/workflow/definitions/schema') return respond(fixtureSchema(definitionFields)), true;
      if (path === '/workflow/definitions') return respond(fixturePage([{...definition}], definitionFields, url)), true;
      if (path === '/workflow/definitions/definition1') return respond(detail()), true;
      if (path === '/workflow/definitions/definition1/draft') {
        if (data.expectedRevision !== definition.draftRevision) throw Error('Stale workflow draft revision');
        draftXml = data.bpmnXml; draftContract = structuredClone(data.formContract);
        Object.assign(definition, {name: data.name, category: data.category, description: data.description,
          draftRevision: definition.draftRevision + 1, unpublishedChanges: true});
        return respond(detail()), true;
      }
      if (/\/versions\/\d$/.test(path)) {
        const version = Number(path.split('/').at(-1));
        return respond({version: versions.find(item => item.version === version), bpmnXml: xml, name: '历史名称',
          formContract: {...contract, variables: version === 1 ? variables : [...variables, {name: 'note', label: '补充说明',
            type: 'string', scope: 'both', required: false}]}}), true;
      }
      if (path.endsWith('/start-form')) {
        const version = Number(url.searchParams.get('version') ?? 2);
        return respond({definitionVersion: version, formContract: contract, values: {}}), true;
      }
      if (path === '/workflow/definitions/definition1/instances') return respond({...instance, definitionVersion: data.version}), true;
      throw Error('Unimplemented workflow fixture: ' + path);
    }};
}
