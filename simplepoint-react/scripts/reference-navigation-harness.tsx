import {useState} from 'react';
import {createRoot} from 'react-dom/client';
import {App} from 'antd';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {HashRouter, Route, Routes, useNavigate} from 'react-router';
import NavigateBar from '../modules/simplepoint-host/src/layouts/navigation-bar';
import {I18nContext, type I18nContextValue} from '../modules/simplepoint-host/src/layouts/i18n/I18nProvider';
import {RetainedRemoteView} from '../modules/simplepoint-host/src/components/RetainedRemoteView';
import {useRefreshKeyMap} from '../modules/simplepoint-host/src/hooks/useRefreshKeyMap';
import {NotificationProvider} from '../modules/simplepoint-host/src/notifications/NotificationProvider';
import {usePageLocation, usePageRetention} from '../libs/shared/page/hooks';
import {formReferenceLocation, readFormReferenceLocation} from '../libs/components/FormRuntime/referenceLocation';
import {pageTabIdentity} from '../libs/shared/page/viewIdentity';
import ApplicationRuntime from '../modules/simplepoint-app/src/views/FormRuntime';
import {bindQueryScope} from '../libs/shared/api/queryScope';

/** Actual host navigation and reference page, with isolated HTTP fixtures and no production access. */
const route = '/app/runtime/sales/trades';
const source = {applicationId: 'sales', pageId: 'trades', formId: 'trade', formVersion: 3};
const id = (index: number) => `aaaaaaaa-1111-2222-3333-${String(index).padStart(12, '0')}`;
const link = (index: number) => formReferenceLocation(source, 'customer', id(index));
const requests: {url: string; signal?: AbortSignal | null; finish: (response: Response) => void}[] = [];
const unexpected: string[] = [];
const mountCounts = new Map<string, number>();
const drafts = new Map<string, (value: string) => void>();
const targetRecords = new Map<string, {id: string; version: number; state: 'SUBMITTED'; values: {name: string}}>();
const boot = crypto.randomUUID();
localStorage.setItem('sp.tenantId', 'fixture');
localStorage.setItem('sp.contextId:fixture', 'fixture-context');
const originalFetch = window.fetch.bind(window);
window.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), window.location.href);
  if (url.pathname.includes('/references/customer/')) {
    return new Promise<Response>(finish => requests.push({url: url.pathname + url.search, signal: init?.signal, finish}));
  }
  if (url.pathname === '/userinfo') return Response.json({sub: 'fixture', name: 'Fixture'});
  if (url.pathname.startsWith('/app/runtime/applications/customers/pages/customers/records')) {
    if (url.pathname.endsWith('/schema')) return Response.json({schema: {type: 'object', properties: {
      name: {type: 'string', title: '客户名称'},
    }}, buttons: []});
    if (url.pathname.endsWith('/relations')) return Response.json([]);
    return Response.json(targetRecords.get(url.pathname.split('/').at(-1)!), {status: 200});
  }
  if (url.pathname === '/common/tenants/authorization-context-id') return Response.json('fixture-context');
  if (url.pathname === '/common/tenants/current') return Response.json([{tenantId: 'fixture', tenantName: 'Fixture', tenantType: 'PLATFORM'}]);
  if (url.pathname === '/common/tenants/current-roles') return Response.json([]);
  if (url.pathname.startsWith('/common/personal/')) return Response.json({items: [], total: 0});
  if (url.pathname.startsWith('/static/') || url.pathname.startsWith('/__')) return originalFetch(input, init);
  unexpected.push(url.pathname);
  return Response.json({}, {status: 404});
};
const client = new QueryClient({defaultOptions: {queries: {retry: false}}});
bindQueryScope(client);
const translate = (key: string, fallback?: string | Record<string, unknown>) => typeof fallback === 'string' ? fallback : key;
const i18n: I18nContextValue = {locale: 'zh-CN', setLocale: () => {}, languages: [], messages: {}, t: translate,
  loading: false, ready: true, refresh: async () => {}, ensure: async () => {}};
window.spI18n = i18n;
const routes = [{id: 'fixture-trades', path: route, label: '交易', title: 'fixture.trades', component: 'app/FormRuntime', icon: 'TableOutlined'}];

function Entry() {
  const location = usePageLocation(), key = pageTabIdentity(location);
  const [draft, setDraft] = useState('');
  const [mount] = useState(() => {const count = (mountCounts.get(key) ?? 0) + 1; mountCounts.set(key, count); return count;});
  drafts.set(key, setDraft);
  usePageRetention(!draft, !!draft);
  const selection = readFormReferenceLocation(location);
  return <div data-entry={key} data-mount={mount}>
    {selection.state === 'none' ? <span data-draft>{draft || 'EMPTY-DRAFT'}</span> : <ApplicationRuntime/>}
  </div>;
}

function Harness() {
  const navigate = useNavigate(), refreshKeys = useRefreshKeyMap();
  Object.assign(window, {referenceNavigation: {
    boot, link, navigate: (location: string) => navigate(location),
    draft: (value: string) => drafts.get(route)?.(value),
    requests: () => requests.map(request => ({url: request.url, aborted: !!request.signal?.aborted})),
    unexpected: () => unexpected,
    finish: (index: number, label = '客户', status = 200) => {
      const recordId = new URL(requests[index].url, location.origin).pathname.split('/').at(-1)!;
      const record = {id: recordId, version: 1, state: 'SUBMITTED' as const, values: {name: label}};
      if (status === 200) targetRecords.set(recordId, record);
      requests[index].finish(Response.json(status === 200 ? {
      form: {applicationId: 'customers', pageId: 'customers', formId: 'customer', formVersion: 1, pageName: '客户',
        formName: '客户', sceneId: 'main', resourceCode: 'customers', capabilities: {canCreate: false, canEdit: false, canDelete: false, canStartProcess: false},
        document: {contractVersion: '1', fields: [{fieldId: 'name', label: '客户名称', type: 'STRING', submitRequired: false, choices: []}],
          details: [], rules: [], scenes: [{sceneId: 'main', code: 'main', name: '客户', type: 'MAIN', root: {
            componentId: 'root', componentType: 'layout.form', componentVersion: '1', label: '客户', columnSpan: 24,
            readOnly: false, properties: {}, children: [{componentId: 'name', componentType: 'field.text', componentVersion: '1',
              label: '客户名称', fieldId: 'name', columnSpan: 24, readOnly: false, properties: {}, children: []}]}}]}},
      record,
    } : {message: 'Access denied', messageKey: 'fixture.denied'}, {status}));},
  }});
  return <NavigateBar data={routes}><Routes>
    <Route path="/dashboard" element={<span>HOME</span>}/>
    <Route path={route} element={<RetainedRemoteView component={Entry} t={translate} path={route} refreshKeys={refreshKeys}/>}/>
    <Route path="*" element={<span>NOT-AUTHORIZED</span>}/>
  </Routes></NavigateBar>;
}

createRoot(document.getElementById('root')!).render(<QueryClientProvider client={client}><I18nContext.Provider value={i18n}>
  <App><NotificationProvider enabled={false}><HashRouter><Harness/></HashRouter></NotificationProvider></App>
</I18nContext.Provider></QueryClientProvider>);
