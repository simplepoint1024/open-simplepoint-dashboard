import {useMemo, useState} from 'react';
import {createRoot} from 'react-dom/client';
import {App} from 'antd';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {mkT} from '../libs/shared/hooks/useI18n';
import {bindQueryScope} from '../libs/shared/api/queryScope';
import PageSection from '../libs/shared/page/Section';
import FormProperties from '../libs/components/FormDesigner/FormProperties';
import {DesignerRelationContext} from '../libs/components/FormDesigner/relations/Context';
import {relationTargetDirectory, type InverseSourceMetadata} from '../libs/components/FormDesigner/relations/catalog';
import type {DesignDocument} from '../libs/components/FormDesigner/contracts';

/** Real form properties and inverse editor, with an isolated manually completed metadata directory. */
const client = new QueryClient({defaultOptions: {queries: {retry: false}}});
bindQueryScope(client);
window.spI18n = {t: mkT({}), locale: 'zh-CN', messages: {}, ensure: async () => {}, setLocale: () => {}};
window.fetch = async () => new Response('{}', {headers: {'Content-Type': 'application/json'}});
const source = {applicationId: 'sales', formId: 'trades', accessResourceId: 'trade-page'};
const initial: DesignDocument = {contractVersion: '1', indexes: [], rules: [], fields: [], scenes: [],
  presentation: {listFieldOrder: [], inverseViews: [{viewId: 'trades', label: '交易记录・日本語😀', source,
    sourceRelationId: 'customer', allowCreate: false, allowEdit: false, allowDelete: false, allowUnlink: false}]}};
const reads: {signal: AbortSignal; finish: (metadata: InverseSourceMetadata) => void}[] = [];

function Harness() {
  const [document, setDocument] = useState(initial), [selected, setSelected] = useState(true);
  const [generation, setGeneration] = useState(0), [commits, setCommits] = useState(0), [open, setOpen] = useState(true);
  const directory = useMemo(() => relationTargetDirectory(`inverse-fixture-${generation}`, '/fixture', 'zh-CN', {
    search: async (_keyword, page, size) => ({page, size, total: 1, records: [{target: source, pageId: 'trade-page', applicationName: '销售', pageName: '交易', formName: '交易'}]}),
    resolve: async () => [{target: source, pageId: 'trade-page', applicationName: '销售', pageName: '交易', formName: '交易'}],
    describe: async target => ({target, pageId: 'trade-page', formVersion: 3, fields: []}),
    inverseSource: (_source, signal) => new Promise(finish => reads.push({signal, finish})),
  }), [generation]);
  Object.assign(window, {inverseAuthoring: {document: () => document, commits: () => commits, setSelected, setGeneration,
    reads: () => reads.map(read => ({aborted: read.signal.aborted})),
    finish: (index: number, nullable = true) => reads[index].finish({source, formVersion: 3, relations: [{relationId: 'customer', label: '客户', nullable}]}),
  }});
  return <PageSection selected={selected}><DesignerRelationContext.Provider value={directory}>
    <FormProperties document={document} disabled={false} open={open} onClose={() => setOpen(false)}
      onChange={next => {setDocument(next); setCommits(value => value + 1); return true;}}/>
  </DesignerRelationContext.Provider></PageSection>;
}
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={client}><App><Harness/></App></QueryClientProvider>);
