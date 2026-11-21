import {useRef, useState} from 'react';
import {useOperationScope} from '../libs/shared/hooks/useOperationScope';
import {createRoot} from 'react-dom/client';
import {App} from 'antd';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {mkT} from '../libs/shared/hooks/useI18n';
import {bindQueryScope} from '../libs/shared/api/queryScope';
import PageSection from '../libs/shared/page/Section';
import RecordTable from '../libs/components/FormRuntime/RecordTable';
import InverseRecords from '../libs/components/FormRuntime/InverseRecords';
import type {ApplicationFormRuntimeView} from '../libs/components/FormRuntime/contracts';

/** Isolated browser fixture. All API calls are intercepted; no real accounts or writes are used. */
const client = new QueryClient({defaultOptions: {queries: {retry: false}}});
localStorage.setItem('sp.tenantId', 'fixture-tenant');
localStorage.setItem('sp.contextId:fixture-tenant', 'fixture-context');
bindQueryScope(client);
window.spI18n = {t: mkT({}), locale: 'zh-CN', messages: {}, ensure: async () => {}, setLocale: () => {}};
const id = 'aaaaaaaa-1111-2222-3333-000000000001';
const calls: {url: string; method: string; body?: unknown; aborted: () => boolean}[] = [];
const held: {finish: () => void; signal?: AbortSignal | null}[] = [];
let holdDetails = false, denyCatalog = false, failUnlink = false, owned = false, approval = false, denyEdits = false;
const row = {id, version: 2, state: 'DRAFT', values: {name: '交易 日本', customer: id}};
const descriptor: ApplicationFormRuntimeView = {
  applicationId: 'sales', pageId: 'trades', pageName: '交易', formId: 'trade-form', formName: '交易',
  formVersion: 3, sceneId: 'main', resourceCode: 'trades',
  capabilities: {canCreate: true, canEdit: true, canDelete: true, canStartProcess: false},
  document: {contractVersion: '1', fields: [
    {fieldId: 'name', label: '名称', type: 'STRING', submitRequired: true, choices: []},
    {fieldId: 'customer', label: '客户', type: 'STRING', submitRequired: false, choices: []},
  ], rules: [], details: [], scenes: [{sceneId: 'main', code: 'main', name: '交易', type: 'MAIN', root: {
    componentId: 'root', componentType: 'layout.form', componentVersion: '1', columnSpan: 24,
    readOnly: false, properties: {}, children: [...['name', 'customer'].map(fieldId => ({
      componentId: fieldId, fieldId, componentType: 'field.text', componentVersion: '1', columnSpan: 24,
      readOnly: false, properties: {}, children: [],
    })), {componentId: 'save', componentType: 'action.save', componentVersion: '1', columnSpan: 24,
      readOnly: false, properties: {}, children: []}],
  }}]},
};
const detailRows = [
  {id: 'bbbbbbbb-1111-2222-3333-000000000001', version: 5, values: {customer: 'cccccccc-1111-2222-3333-000000000001', note: '保留其他行'}},
  {id: 'bbbbbbbb-1111-2222-3333-000000000002', version: 7, values: {customer: id, note: '解除这一行'}},
];
function currentDescriptor() {
  const source = structuredClone(descriptor);
  if (approval) source.capabilities.canStartProcess = true;
  if (!owned) return source;
  source.document.details = [{detailId: 'lines', label: '交易明细', fields: [
    {...source.document.fields[1]}, {fieldId: 'note', label: '说明', type: 'STRING', submitRequired: false, choices: []},
  ]}];
  source.document.scenes[0].root.children.push({componentId: 'lines', componentType: 'data.detail',
    componentVersion: '1', columnSpan: 24, readOnly: false, children: [],
    properties: {detailId: {kind: 'STRING', value: 'lines'}}});
  return source;
}
window.fetch = async (input, options = {}) => {
  const url = new URL(String(input), location.href), method = options.method ?? 'GET';
  const body = typeof options.body === 'string' ? JSON.parse(options.body) : undefined;
  calls.push({url: url.pathname + url.search, method, body, aborted: () => !!options.signal?.aborted});
  const currentRow = owned ? {...row, values: {...row.values, lines: detailRows}} : row;
  const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), {status, headers: {'Content-Type': 'application/json'}});
  if (url.pathname === '/csrf') return json({token: 'isolated-fixture', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf'});
  if (url.pathname.endsWith('/submissions/definitions')) return json({content: [{id: 'approval', version: 2, name: '交易审批'}], totalElements: 1, page: 0, size: 2});
  if (url.pathname.endsWith('/submissions/preparation')) return json({definitionId: 'approval', definitionVersion: 2,
    title: '交易审批', form: currentDescriptor(), variables: [], values: {}, ccUserIds: []});
  if (url.pathname.endsWith('/submissions') && method === 'POST' || url.pathname.endsWith('/submissions/accepted')) return json({
    id: 'accepted', title: '交易审批', applicationId: 'sales', pageId: 'trades', formId: 'trade-form', formVersion: 3,
    recordId: id, recordVersion: 3, definitionId: 'approval', definitionVersion: 2,
    status: method === 'POST' ? 'ACCEPTED' : 'SUCCEEDED', step: method === 'POST' ? 'PREPARED' : 'STARTED',
    processInstanceId: method === 'POST' ? undefined : 'process', createdAt: '2026-10-29T00:00:00Z',
    updatedAt: method === 'POST' ? '2026-10-29T00:00:00Z' : '2026-10-29T00:00:01Z', attempt: 1,
  });
  if (url.pathname.endsWith('/relations')) return json(denyCatalog ? [] : [
    {viewId: 'trades', label: '关联交易'}, {viewId: 'orders', label: '关联订单'},
  ]);
  if (url.pathname.endsWith('/records/runtime')) return json({source: currentDescriptor(), ...(owned ? {detailId: 'lines'} : {}), fieldId: 'customer', canUnlink: true});
  if (url.pathname.endsWith('/records/schema')) return json({schema: {type: 'object', properties: {
    id: {type: 'string', readOnly: true, 'x-ui': {'x-list-visible': false}},
    version: {type: 'integer', readOnly: true, 'x-ui': {'x-list-visible': false}},
    recordState: {type: 'string', readOnly: true, 'x-ui': {'x-list-visible': true}},
    name: {type: 'string', title: '名称', 'x-ui': {'x-list-visible': true}},
    customer: {type: 'string', title: '客户', 'x-ui': {'x-list-visible': true}},
  }, 'x-query': {version: 1, fields: {}, relations: [], maxPageSize: 500}}, buttons: [
    {key: 'add', title: 'Add', icon: 'PlusOutlined', argumentMinSize: 0, argumentMaxSize: 0, sort: 0},
    ...(!denyEdits ? [{key: 'edit', title: 'Edit', icon: 'EditOutlined', argumentMinSize: 1, argumentMaxSize: 1, sort: 1}] : []),
    {key: 'view', title: 'View', icon: 'EyeOutlined', argumentMinSize: 1, argumentMaxSize: 1, sort: 2},
    ...(approval ? [{key: 'startProcess', title: 'Submit', icon: 'SendOutlined', argumentMinSize: 1, argumentMaxSize: 1, sort: 5}] : []),
    ...(url.pathname.includes('/relations/') ? [{key: 'unlink', title: 'Unlink', icon: 'DisconnectOutlined', argumentMinSize: 1, argumentMaxSize: 1, sort: 4}] : []),
    {key: 'delete', title: 'Delete', icon: 'DeleteOutlined', argumentMinSize: 1, argumentMaxSize: 1, sort: 3},
  ]});
  if (url.pathname.endsWith('/records/query')) return json({page: 0, size: 50, total: 1, records: [currentRow]});
  if (url.pathname.endsWith('/records/' + id) && method === 'GET') {
    if (holdDetails) return new Promise(resolve => held.push({signal: options.signal, finish: () => resolve(json(currentRow))}));
    return json(currentRow);
  }
  if (url.pathname.endsWith('/records/' + id) && method === 'PUT') {
    Object.assign(row.values, body.values); row.version++;
    return json({recordId: id, version: row.version, state: row.state, currentRecord: row});
  }
  if (url.pathname.endsWith('/unlink') && failUnlink) return json({message: 'fixture uncertain response'}, 503);
  if (['POST', 'PUT', 'DELETE'].includes(method) && url.pathname.includes('/relations/')) return json({recordId: id, version: 3, currentRecord: row});
  return json({});
};

function Harness() {
  const [parent, setParent] = useState('parent-a');
  const [selected, setSelected] = useState(true);
  const [mode, setMode] = useState('table'), [nestedLocked, setNestedLocked] = useState(false);
  Object.assign(window, {inverseTable: {
    target: () => {approval = false; owned = false; denyEdits = false; holdDetails = false; client.clear(); setMode('record');},
    denyEdits: () => {denyEdits = true; void client.invalidateQueries();},
    calls: () => calls.map(({url, method, body, aborted}) => ({url, method, body, aborted: aborted()})),
    enableSubmission: () => {approval = true; owned = false; client.clear(); setMode('approval');}, setOwned: () => {owned = true; client.clear(); setMode('owned');}, failUnlink: (value: boolean) => {failUnlink = value;}, setParent, setSelected, setMode, locked: () => nestedLocked, denyCatalog: (value: boolean) => {denyCatalog = value; void client.invalidateQueries();}, hold: (value: boolean) => {holdDetails = value;},
    held: () => held.map(item => ({aborted: !!item.signal?.aborted})),
    finish: (index: number) => held[index].finish(),
  }});
  return <PageSection selected={selected}><OperationProbe/><div id="fixture" style={{height: 700, width: 1100}}>
    {mode === 'record' ? <RecordTable descriptor={descriptor} recordId={id} showRelations={false}
      onInteractionLockChange={setNestedLocked}/> : mode !== 'table' ? <InverseRecords key={mode} parent={{...descriptor, pageId: 'customers', formId: 'customer-form', formVersion: 7}}
      recordId={id} onInteractionLockChange={setNestedLocked}/> : <RecordTable descriptor={descriptor} showRelations={false} endpoint={{
      baseUrl: `/app/runtime/applications/sales/pages/customers/records/${parent}/relations/trades/records`,
      params: {parentFormId: 'customer-form', parentFormVersion: '7'},
      preferenceKey: 'fixture.inverse.trades', initialValues: {customer: parent},
    }}/> }
  </div></PageSection>;
}

function OperationProbe() {
  const operation = useOperationScope('fixture-write');
  const pending = useRef<AbortController | undefined>(undefined);
  Object.assign(window, {operationProbe: {
    begin: () => {pending.current = operation.begin(); return !!pending.current;},
    aborted: () => pending.current?.signal.aborted,
    finish: () => {if (pending.current) operation.finish(pending.current);},
  }});
  return null;
}

createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={client}><App><Harness/></App></QueryClientProvider>,
);
