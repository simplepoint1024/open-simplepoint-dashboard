import {useMemo, useState} from 'react';
import {createRoot} from 'react-dom/client';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {bindQueryScope} from '../libs/shared/api/queryScope';
import {useQueryScope} from '../libs/shared/hooks/useQueryScope';
import PageSection from '../libs/shared/page/Section';
import {ReferenceRuntimeContext, type ReferenceSources} from '../libs/components/FormComponents/Reference/RuntimeContext';
import {referenceSource} from '../libs/components/FormComponents/Reference/source';
import {useLiveReferenceValues} from '../libs/components/FormRuntime/useLiveReferenceValues';
import type {ApplicationFormDocument, FormFieldDefinition} from '../libs/components/FormRuntime/contracts';
import type {LiveReferenceView} from '../libs/components/FormRuntime/liveReferenceValues';

/** Isolated browser fixture; no production service, account or credential is accessed. */
const client = new QueryClient({defaultOptions: {queries: {retry: false}}});
bindQueryScope(client);
const id = (index: number) => `aaaaaaaa-1111-2222-3333-${String(index).padStart(12, '0')}`;
const calls: {relation: string; ids: string[]; signal: AbortSignal; finish: (rows: LiveReferenceView[]) => void}[] = [];
const fields = (relationId: string): FormFieldDefinition[] => [
  {fieldId: 'reference', label: 'Reference', type: 'UUID', submitRequired: false, choices: [], reference: {
    relationId, allowDrillThrough: false, mappings: [{mappingId: 'live', destinationFieldId: 'amount', mode: 'LIVE'}]}},
  {fieldId: 'amount', label: 'Amount', type: 'DECIMAL', precision: 20, scale: 2, submitRequired: false,
    choices: [], referenceValueMode: 'LIVE'},
];
const node = (componentId: string, componentType: string, extra = {}) => ({componentId, componentType,
  componentVersion: '1', columnSpan: 24, readOnly: true, properties: {}, children: [], ...extra});
const document: ApplicationFormDocument = {contractVersion: '1', fields: fields('customer'), rules: [],
  details: [{detailId: 'lines', label: 'Lines', fields: fields('product')}], scenes: [{sceneId: 'main', code: 'main',
    name: 'Live', type: 'MAIN', root: node('root', 'layout.form', {children: [node('amount', 'field.decimal', {fieldId: 'amount'}),
      node('lines', 'data.detail', {properties: {detailId: {kind: 'STRING', value: 'lines'}}})]})}]};
const initial = {reference: id(0), amount: 'MUST-NOT-KEEP', lines: Array.from({length: 40}, (_, index) => ({
  id: id(100 + index), version: 1, values: {reference: id(index + 1), amount: 'MUST-NOT-KEEP'},
}))};
function View({value}: {value: Record<string, unknown>}) {
  const live = useLiveReferenceValues(document, 'main', value);
  Object.assign(window, {liveResult: {refresh: live.refresh, value: live.values, loading: live.loading, failed: live.failed}});
  return <pre id="result">{JSON.stringify(live.values)}</pre>;
}
function Harness() {
  const [state, setState] = useState({selected: true, frozen: false, owner: 0});
  const [value, setValue] = useState<Record<string, unknown>>(initial);
  const scope = useQueryScope();
  const sources = useMemo<ReferenceSources>(() => relation => ({endpoint: '/isolated',
    source: referenceSource(JSON.stringify([scope, state.owner, relation]), {
      search: async () => ({page: 0, size: 50, total: 0, records: []}), resolve: async () => [],
    }), live: (ids, signal) => new Promise(finish => calls.push({relation, ids, signal, finish})),
  }), [scope, state.owner]);
  Object.assign(window, {liveFixture: {
    set: (patch: Partial<typeof state>) => setState(previous => ({...previous, ...patch})),
    setReference: (index: number | null) => setValue(previous => ({...previous, reference: index === null ? null : id(index)})),
    oversize: () => setValue(previous => ({...previous, lines: Array.from({length: 41}, (_, index) => ({id: id(200+index), values: {reference: id(index), amount: 'STALE'}}))})),
    reverse: () => setValue(previous => ({...previous, lines: [...previous.lines as any[]].reverse()})),
    calls: () => calls.map(call => ({relation: call.relation, ids: call.ids, aborted: call.signal.aborted})),
    finish: (index: number, amount: string, invalid = false) => calls[index].finish(calls[index].ids.map(targetRecordId => ({
      targetRecordId: invalid ? id(999) : targetRecordId, targetVersion: 1, values: {amount: {kind: 'DECIMAL', value: amount}},
    }))), input: () => value,
  }});
  return <PageSection selected={state.selected}><ReferenceRuntimeContext.Provider value={state.frozen ? undefined : sources}>
    <View value={value}/></ReferenceRuntimeContext.Provider></PageSection>;
}
createRoot(window.document.getElementById('root')!).render(<QueryClientProvider client={client}><Harness/></QueryClientProvider>);
