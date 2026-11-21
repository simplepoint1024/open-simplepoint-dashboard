import type {CalculationPreviewLoader, CalculationPreviewResult} from '../libs/components/FormRuntime/calculationPreview';
import {useMemo, useRef, useState} from 'react';
import {createRoot} from 'react-dom/client';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {App, ConfigProvider} from 'antd';
import {bindQueryScope} from '../libs/shared/api/queryScope';
import {useQueryScope} from '../libs/shared/hooks/useQueryScope';
import PageSection from '../libs/shared/page/Section';
import FormRuntime from '../libs/components/FormRuntime';
import type {SFormHandle} from '../libs/components/SForm';
import type {ReferenceSources} from '../libs/components/FormComponents/Reference/RuntimeContext';
import {referenceSource} from '../libs/components/FormComponents/Reference/source';
import type {ApplicationFormDocument, FormFieldDefinition} from '../libs/components/FormRuntime/contracts';
import type {MappingIntent, MappingView, MappingSourceRow} from '../libs/components/FormRuntime/referenceMapping/model';
import {detailPayload} from '../libs/components/FormComponents/Detail/payload';

/** Actual FormRuntime/SForm/EntitySelect fixture with deterministic IO, no production writes. */
const client = new QueryClient({defaultOptions: {queries: {retry: false}}});
bindQueryScope(client);
const id = (index: number) => `aaaaaaaa-1111-2222-3333-${String(index).padStart(12, '0')}`;
const calls: {relation: string; target: string | null; defaults: string[]; sourceRow?: MappingSourceRow; signal: AbortSignal; finish: (view: MappingView) => void}[] = [];
const previews: {values: Record<string, unknown>; mappings?: MappingIntent; signal: AbortSignal; finish: (value: CalculationPreviewResult) => void}[] = [];
const preview: CalculationPreviewLoader = (values, signal, mappings) => new Promise(finish => previews.push({values, mappings, signal, finish}));
const writes: {values: Record<string, unknown>; mappings?: MappingIntent}[] = [];
const fields: FormFieldDefinition[] = [
  {fieldId: 'customer', label: '客户', type: 'UUID', choices: [], submitRequired: false, reference: {
    relationId: 'customer-rel', allowDrillThrough: false, mappings: [
      {mappingId: 'copy', destinationFieldId: 'name', mode: 'SERVER_SNAPSHOT'},
      {mappingId: 'default', destinationFieldId: 'amount', mode: 'EDITABLE_DEFAULT'},
    ]}},
  {fieldId: 'name', label: '客户名称', type: 'STRING', choices: [], submitRequired: false, referenceValueMode: 'SERVER_SNAPSHOT'},
  {fieldId: 'amount', label: '金额', type: 'DECIMAL', precision: 20, scale: 2, choices: [], submitRequired: false, referenceValueMode: 'EDITABLE_DEFAULT'},
];
const node = (componentId: string, componentType: string, extra = {}) => ({componentId, componentType,
  componentVersion: '1', columnSpan: 24, readOnly: false, properties: {}, children: [], ...extra});
const fieldNodes = [node('customer-field', 'field.reference', {fieldId: 'customer'}), node('name-field', 'field.text', {fieldId: 'name'}),
  node('amount-field', 'field.number', {fieldId: 'amount'})];
const document: ApplicationFormDocument = {contractVersion: '1', fields, rules: [],
  details: [{detailId: 'lines', label: '明细', fields: fields.map(field => field.reference
    ? {...field, reference: {...field.reference, relationId: 'product-rel'}} : field)}], scenes: [
    {sceneId: 'main', code: 'main', name: '主表', type: 'MAIN', root: node('root', 'layout.form', {children: [...fieldNodes,
      node('lines', 'data.detail', {properties: {detailId: {kind: 'STRING', value: 'lines'}}}),
      node('open', 'action.open-dialog', {label: '附加信息', targetSceneId: 'secondary'}), node('save', 'action.save', {label: '保存'}),
    ]})},
    {sceneId: 'secondary', code: 'secondary', name: '附加信息', type: 'DIALOG', root: node('secondary-root', 'layout.form', {children: fieldNodes})},
  ]};
const seed = {customer: id(1), amount: '12.00', name: '原客户', lines: []};
const fixedDocument: ApplicationFormDocument = {...document, fields: [
  {...fields[0], access: {readOnly: true}}, ...fields.slice(1),
]};
function Harness() {
  const [state, setState] = useState({selected: true, frozen: false, owner: 0, preview: false, fixed: false});
  const [value, setValue] = useState<Record<string, unknown>>(seed);
  const form = useRef<SFormHandle>(null), scope = useQueryScope();
  const sources = useMemo<ReferenceSources>(() => relation => ({endpoint: '/isolated',
    source: referenceSource(JSON.stringify([scope, state.owner, relation]), {
      search: async () => ({page: 0, size: 50, total: 3, records: [1, 2, 3].map(index => ({id: id(index), version: 1, values: {}, label: `客户${index}`}))}),
      resolve: async ids => ids.map(value => ({id: value, version: 1, values: {}, label: `客户${Number(value.slice(-12))}`})),
    }), mapping: (target, defaults, signal, sourceRow) => new Promise(finish => calls.push({relation, target, defaults, sourceRow, signal, finish})),
  }), [scope, state.owner]);
  Object.assign(window, {mappingFixture: {
    set: (patch: Partial<typeof state>) => setState(previous => ({...previous, ...patch})),
    edit: (patch: Record<string, unknown>) => setValue(previous => ({...previous, ...patch})),
    previews: () => previews.map(call => ({values: call.values, mappings: call.mappings, aborted: call.signal.aborted})),
    finishPreview: (index: number, label: string) => {const call = previews[index]; call.finish({values: {name: label},
      details: {lines: (call.values.lines as any[] ?? []).map(() => ({name: label}))}});},
    calls: () => calls.map(call => ({relation: call.relation, target: call.target, defaults: call.defaults, sourceRow: call.sourceRow, aborted: call.signal.aborted})),
    finish: (index: number, amount = '9007199254740993.12', version = 7, invalid = false) => {
      const call = calls[index]; call.finish({observation: {revision: 'a'.repeat(64), targetRecordId: call.target, targetVersion: call.target ? version : null},
        snapshots: invalid ? {} : {name: call.target ? {kind: 'STRING', value: `客户名称${Number(call.target.slice(-12))}漢字😀`} : {kind: 'NULL'}},
        defaults: call.defaults.length ? {amount: call.target ? {kind: 'DECIMAL', value: amount} : {kind: 'NULL'}} : {}});
    }, input: () => value, writes: () => writes, submit: () => form.current?.submit(),
  }});
  return <PageSection selected={state.selected}><FormRuntime key={state.owner} document={state.fixed ? fixedDocument : document} sceneId="main" value={value}
    fixedReferenceField={state.fixed ? 'customer' : undefined}
    calculationPreview={state.preview ? preview : undefined} readOnly={state.frozen} formRef={form} references={sources} onChange={setValue}
    onAction={(_action, values, mappings) => {writes.push({values: detailPayload(document, values), mappings});}}
    onSubmit={(values, mappings) => {writes.push({values: detailPayload(document, values), mappings});}}/></PageSection>;
}
createRoot(window.document.getElementById('root')!).render(<ConfigProvider><App><QueryClientProvider client={client}><Harness/></QueryClientProvider></App></ConfigProvider>);
