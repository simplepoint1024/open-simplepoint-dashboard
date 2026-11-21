import {useMemo, useState} from 'react';
import {createRoot} from 'react-dom/client';
import {App} from 'antd';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {bindQueryScope} from '../libs/shared/api/queryScope';
import {useQueryScope} from '../libs/shared/hooks/useQueryScope';
import PageSection from '../libs/shared/page/Section';
import FormDesigner from '../libs/components/FormDesigner';
import type {ComponentDescriptor, DesignDocument} from '../libs/components/FormDesigner/contracts';
import type {FormCollectionRelation, FormRelationTarget} from '../libs/components/FormDesigner/relationContracts';
import {relationTargetDirectory, type RelationTargetMetadata} from '../libs/components/FormDesigner/relations/catalog';
import EnumOverlayHarness from './sform-enum-overlay-harness';
import '../libs/components/Simplepoint.css';

/** Real designer and property editors, with an isolated manually completed metadata directory. */
const client = new QueryClient({defaultOptions: {queries: {retry: false}}});
bindQueryScope(client);
const reads: {target: FormRelationTarget; signal: AbortSignal; finish: (metadata: RelationTargetMetadata) => void; fail: (error: Error) => void}[] = [];
const descriptor = (type: string, container = false, types: ComponentDescriptor['supportedFieldTypes'] = []): ComponentDescriptor => ({
  type, version: '1', container, fieldRequired: Boolean(types.length), supportedFieldTypes: types, allowedChildTypes: [], allowedParentTypes: [], propertySchema: {},
});
const components = [descriptor('layout.form', true), descriptor('layout.group', true), descriptor('field.reference', false, ['UUID']), descriptor('data.detail')];
const node = (componentId: string, componentType: string, extra = {}) => ({componentId, componentType, componentVersion: '1', columnSpan: 24,
  readOnly: false, properties: {}, children: [], ...extra});
const target = (id = 'customers'): FormRelationTarget => ({applicationId: 'crm', formId: id, accessResourceId: id + '-page'});
const published: FormCollectionRelation = {contractVersion: '1', cardinality: 'MANY_TO_MANY',
  relationId: 'customer-members', target: target(),
  collectionLabel: {defaultValue: '客户成员', defaultLocale: 'zh-CN', translations: {}}};
const initial: DesignDocument = {contractVersion: '1', indexes: [], rules: [], fields: [{fieldId: 'customer', storageCode: 'customer', label: '客户', storageLabel: '客户',
  type: 'UUID', storageMode: 'REFERENCE', storageRequired: false, submitRequired: false, choices: []},
  {fieldId: 'region', storageCode: 'region', label: '地区', storageLabel: '地区', type: 'STRING', length: 120,
    storageMode: 'STORED', storageRequired: false, submitRequired: false, choices: []},
  {fieldId: 'liveName', label: '实时名称', type: 'STRING', length: 120,
    storageMode: 'TRANSIENT', storageRequired: false, submitRequired: false, choices: []}], details: [{detailId: 'lines', storageCode: 'lines', label: '交易明细',
    fields: [{fieldId: 'product', storageCode: 'product', label: '商品', storageLabel: '商品', type: 'UUID', storageMode: 'STORED', storageRequired: false, submitRequired: false, choices: []}], indexes: []}],
  relations: [{contractVersion: '1', relationId: 'customer-relation', cardinality: 'MANY_TO_ONE', sourceField: {fieldId: 'customer'}, target: target(),
    referenceView: {labelFieldId: 'name', searchFieldIds: ['name'], displayFieldIds: ['balance'], allowDrillThrough: false}}],
  scenes: [{sceneId: 'main', code: 'main', name: '交易', type: 'MAIN', root: node('root', 'layout.form', {children: [
    node('customer-input', 'field.reference', {fieldId: 'customer'}), node('detail-input', 'data.detail', {properties: {detailId: {kind: 'STRING', value: 'lines'}}}),
  ]})}]};

function Harness() {
  const [enumOverlay, setEnumOverlay] = useState(false);
  const [document, setDocument] = useState(initial);
  const [publishedCollections, setPublishedCollections] = useState<FormCollectionRelation[]>();
  const [state, setState] = useState({selected: true, disabled: false, generation: 0});
  const [commits, setCommits] = useState(0);
  const scope = useQueryScope();
  const directory = useMemo(() => relationTargetDirectory(JSON.stringify([scope, state.generation]), '/isolated-relation-catalog', 'zh-CN', {
    search: async (_keyword, page, size) => ({page, size, total: 2, records: ['customers', 'products'].map(id => ({target: target(id), pageId: id,
      applicationName: 'CRM', pageName: id === 'customers' ? '客户・日本語😀' : '商品', formName: id}))}),
    resolve: async ids => ids.map(id => ({target: target(id.replace('-page', '')), pageId: id, applicationName: 'CRM',
      pageName: id === 'customers-page' ? '客户・日本語😀' : '商品', formName: id})),
    describe: (target, signal) => new Promise((finish, fail) => reads.push({target, signal, finish, fail})),
  }), [scope, state.generation]);
  Object.assign(window, {relationAuthoring: {
    showEnums: () => setEnumOverlay(true),
    reset: () => {setDocument(initial); setCommits(0); setState(current => ({...current, generation: current.generation + 1}));},
    startRebind: () => {
      setPublishedCollections([published]);
      setDocument({...initial, relations: [...initial.relations!, published]});
      setCommits(0);
      setState(current => ({...current, generation: current.generation + 1}));
    },
    document: () => document, commits: () => commits,
    set: (patch: Partial<typeof state>) => setState(current => ({...current, ...patch})),
    reads: () => reads.map(read => ({target: read.target, aborted: read.signal.aborted})),
    finish: (index: number, suffix = '') => reads[index].finish({target: reads[index].target, pageId: 'target-page', formVersion: 1, fields: [
      {fieldId: 'name', label: '名称' + suffix, type: 'STRING', searchable: true},
      {fieldId: 'balance', label: '余额' + suffix, type: 'DECIMAL', searchable: false},
      {fieldId: 'active', label: '启用' + suffix, type: 'BOOLEAN', searchable: false},
    ]}),
    fail: (index: number) => reads[index].fail(new Error('Unavailable target')),
    rename: () => setDocument(current => ({...current, fields: current.fields.map(field => field.fieldId === 'customer' ? {...field, label: '外部修改'} : field)})),
  }});
  if (enumOverlay) return <EnumOverlayHarness/>;
  return <PageSection selected={state.selected}><div id="fixture" style={{height: 780, width: 1280}}>
    <FormDesigner key={state.generation} document={document} components={components} writePolicies={['EDITABLE']}
      relations={{...directory, convertibleRelationIds: ['customer-relation'], publishedCollections}} disabled={state.disabled}
      onChange={next => {setDocument(next); setCommits(count => count + 1);}}/>
  </div></PageSection>;
}
createRoot(window.document.getElementById('root')!).render(<QueryClientProvider client={client}><App><Harness/></App></QueryClientProvider>);
