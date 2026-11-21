import {useMemo, useState} from 'react';
import {createRoot} from 'react-dom/client';
import {App} from 'antd';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {bindQueryScope} from '../libs/shared/api/queryScope';
import {useQueryScope} from '../libs/shared/hooks/useQueryScope';
import PageSection from '../libs/shared/page/Section';
import FormRuntime from '../libs/components/FormRuntime';
import FormReferenceValue from '../libs/components/FormComponents/Reference/Value';
import {ReferenceRuntimeContext, type ReferenceDetail, type ReferenceSources} from '../libs/components/FormComponents/Reference/RuntimeContext';
import ReferenceInspector from '../libs/components/FormComponents/Reference/Inspector';
import {ReferenceActionsScope} from '../libs/components/FormComponents/Reference/NavigationContext';
import {usePageSelected} from '../libs/shared/page/hooks';
import {contextualReferenceSource, referenceSource, type ReferenceOption, type ReferencePage} from '../libs/components/FormComponents/Reference/source';
import type {ReferenceSearchContext} from '../libs/components/FormComponents/Reference/inputs';
import {batchReferenceLookups} from '../libs/components/FormComponents/Reference/batch';
import type {FormReferenceSnapshot} from '../libs/components/FormComponents/Reference/snapshots';
import type {ApplicationFormDocument} from '../libs/components/FormRuntime/contracts';

/** Isolated component fixture: no application server, accounts, or production mutations. */
const client = new QueryClient({defaultOptions: {queries: {retry: false}}});
bindQueryScope(client);
const id = (index: number) => `aaaaaaaa-1111-2222-3333-${String(index).padStart(12, '0')}`;
const calls: {ids: string[]; signal: AbortSignal; finish: (rows: ReferenceOption[]) => void; fail: (error: Error) => void}[] = [];
const details: {recordId: string; signal: AbortSignal; finish: (detail: ReferenceDetail) => void}[] = [];
const scenes = new Map<string, (scene: string) => void>();
const pages: string[] = [];
const candidates: {context: ReferenceSearchContext; page: number; signal: AbortSignal; finish: (page: ReferencePage) => void}[] = [];
const field = (fieldId: string) => ({fieldId, label: fieldId, type: 'UUID' as const, submitRequired: false,
  choices: [], reference: {relationId: 'customer', allowDrillThrough: false}});
const component = (componentId: string, componentType: string, extra = {}) => ({componentId, componentType,
  componentVersion: '1', label: componentId, columnSpan: 24, readOnly: false, properties: {}, children: [], ...extra});
const document: ApplicationFormDocument = {contractVersion: '1', fields: [field('customer')], rules: [],
  details: [{detailId: 'lines', label: 'Lines', fields: [field('product')]}], scenes: [{sceneId: 'main', code: 'main',
    name: 'References', type: 'MAIN', root: component('root', 'layout.form', {children: [
      component('customer', 'field.text', {fieldId: 'customer'}),
      component('lines', 'data.detail', {properties: {detailId: {kind: 'STRING', value: 'lines'}}}),
    ]})}]};
const values = {customer: id(0), lines: Array.from({length: 40}, (_, index) => ({id: id(index + 100), version: 1,
  values: {product: id(index + 1)}}))};

const region = {fieldId: 'region', label: '地区', type: 'STRING' as const, submitRequired: false, choices: []};
const contextDocument: ApplicationFormDocument = {...document,
  fields: [region, {...field('customer'), reference: {relationId: 'customer', allowDrillThrough: false, contextual: true,
    contextInputs: [{source: 'FORM', fieldId: 'region', type: 'STRING'}]}}],
  details: [{detailId: 'lines', label: 'Lines', fields: [region, {...field('product'), reference: {relationId: 'customer', allowDrillThrough: false,
    contextual: true, contextInputs: [{source: 'FORM', fieldId: 'region', type: 'STRING'}, {source: 'ROW', fieldId: 'region', type: 'STRING'}]}}]}],
};

function ContextEditor({references}: {references: ReferenceSources}) {
  const [value, setValue] = useState<Record<string, unknown>>({region: '华北', customer: undefined, hidden: 'NEVER-SEND',
    lines: [{values: {region: '東京', product: undefined, secret: 'NEVER-SEND'}},
      {values: {region: '大阪', product: undefined, secret: 'NEVER-SEND'}}]});
  Object.assign(window, {referenceContext: {set: setValue, values: () => value}});
  return <FormRuntime document={contextDocument} sceneId="main" value={value} references={references} onChange={setValue}/>;
}

const frozenLabels: FormReferenceSnapshot[] = Array.from({length: 41}, (_, index) => ({relationId: 'customer',
  detailId: index ? 'lines' : null, sourceRecordId: id(index ? index + 99 : 999), fieldId: index ? 'product' : 'customer',
  targetRecordId: id(index), targetVersion: 3, labelFieldId: 'name', label: `历史名称-${index}`}));

function Harness() {
  const [state, setState] = useState({selected: true, enabled: true, locked: false, pageNavigation: false, mode: 'snapshot', generation: 0});
  const scope = useQueryScope();
  const references = useMemo(() => {
    const source = referenceSource(JSON.stringify([scope, state.generation]), {
      search: async () => {throw new Error('Read-only rendering must never browse the candidate directory');},
      resolve: batchReferenceLookups((ids, signal) => new Promise((finish, fail) => calls.push({ids, signal, finish, fail}))),
    });
    return (_relationId: string, context?: ReferenceSearchContext) => ({endpoint: '/isolated-reference-directory',
      source: context ? contextualReferenceSource(source, context, (_keyword, page, _size, signal) =>
        new Promise(finish => candidates.push({context, page, signal, finish}))) : source,
      pageLocation: (recordId: string) => `/isolated-reference-page/${recordId}`,
      detail: (recordId: string, signal: AbortSignal) => new Promise<ReferenceDetail>(finish => details.push({recordId, signal, finish}))});
  }, [scope, state.generation]);
  Object.assign(window, {referenceDisplay: {
    set: (patch: Partial<typeof state>) => setState(current => ({...current, ...patch})),
    calls: () => calls.map(call => ({ids: call.ids, aborted: call.signal.aborted})),
    finish: (index: number, label: string | null, canDrillThrough = false) => calls[index].finish(label === null ? [] : calls[index].ids.map(id => ({id, version: 1, label: label + id.slice(-2), values: {}, canDrillThrough}))),
    fail: (index: number) => calls[index].fail(new Error('Directory unavailable')),
    invalidate: () => client.invalidateQueries(),
    details: () => details.map(detail => ({recordId: detail.recordId, aborted: detail.signal.aborted})),
    pages: () => pages,
    candidates: () => candidates.map(({context, page, signal}) => ({context, page, aborted: signal.aborted})),
    finishCandidate: (index: number, label: string, total = 61) => candidates[index].finish({page: candidates[index].page, size: 50,
      total, records: [{id: id(500 + index), version: 1, label, values: {}}]}),
    finishDetail: (index: number) => details[index].finish({form: {applicationId: 'customers', pageId: 'customers', pageName: '客户',
      formId: 'customer', formName: '客户', formVersion: 1, sceneId: 'main', resourceCode: 'customers',
      capabilities: {canCreate: false, canEdit: false, canDelete: false, canStartProcess: false}, document},
      record: {id: details[index].recordId, version: 1, state: 'SUBMITTED', values: {customer: id(index + 10), lines: []}}}),
    scene: (recordId: string, scene: string) => scenes.get(recordId)?.(scene),
  }});
  return <PageSection selected={state.selected}>
    <ReferenceRuntimeContext.Provider value={references}>
      <div id="fixture" style={{height: 700, width: 1100}}>
        {state.mode === 'context' ? <ContextEditor references={references}/> : ['inspector', 'editor'].includes(state.mode) ? <ReferenceInspector key={JSON.stringify([scope, state.generation])} enabled={state.enabled}
          onOpenPage={state.pageNavigation ? (binding, recordId) => {pages.push(binding.pageLocation!(recordId));} : undefined}
          renderDetail={detail => <InspectorScene detail={detail} references={references}/>}>
            <OwnerSelection/>
            <ReferenceActionsScope disabled={state.locked}>
              {state.mode === 'editor' ? <FormRuntime document={document} sceneId="main" value={{customer: id(0), lines: []}} references={references}/>
                : <FormReferenceValue value={id(0)} relationId="customer"/>}
            </ReferenceActionsScope>
          </ReferenceInspector> : state.mode === 'values' ? <>{Array.from({length: 40}, (_, index) => <div key={index}>
          <FormReferenceValue value={id(index)} relationId="customer"/></div>)}
          <div id="masked"><FormReferenceValue value="***" relationId="customer"/></div></>
          : <FormRuntime document={document} sceneId="main" value={values} readOnly
            referenceSnapshots={state.mode === 'frozen' ? frozenLabels : state.mode === 'revoked' ? [] : undefined}
            references={['live', 'frozen', 'revoked'].includes(state.mode) ? references : undefined}/>}
      </div>
    </ReferenceRuntimeContext.Provider>
  </PageSection>;
}

function OwnerSelection() {
  return <span data-owner-selected>{usePageSelected() ? 'selected' : 'background'}</span>;
}

function InspectorScene({detail, references}: {detail: ReferenceDetail; references: ReferenceSources}) {
  const [scene, setScene] = useState('main');
  scenes.set(detail.record.id, setScene);
  return <><span data-inspector-scene>{scene}</span><FormRuntime document={document} sceneId="main"
    value={detail.record.values} readOnly references={references}/></>;
}

createRoot(window.document.getElementById('root')!).render(<QueryClientProvider client={client}><App><Harness/></App></QueryClientProvider>);
