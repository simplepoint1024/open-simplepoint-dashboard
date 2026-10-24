import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {App} from 'antd';
import {useQueryClient} from '@tanstack/react-query';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {get, post, put} from '@simplepoint/shared/api/methods';
import {request} from '@simplepoint/shared/api/client';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import ResourceEditor from './ResourceEditor';
import {resourceAvailabilityColumns} from './resourceAvailability';
import TenantResourceSync from './TenantResourceSync';
import {resourceDraft, resourcePayload, type TenantResource} from './resourceForms';

const endpoint = '/common/tenants/current-resources';
type Props = {tenantId: string; onBlockedChange?: (blocked: boolean) => void};

export function TenantResourceManager(props: Props) {
  const scope = useQueryScope();
  return scope[0] === props.tenantId ? <ResourceManagerSession key={JSON.stringify(scope)} {...props}/> : null;
}

function ResourceManagerSession({tenantId, onBlockedChange}: Props) {
  const {t, locale} = useI18n(), {message} = App.useApp();
  const client = useQueryClient(), scope = useQueryScope(), operation = useOperationScope();
  const permissions = useSchema(endpoint, {retry: false, staleTime: 30_000});
  const rootLocked = useRef(false), sync = useRef<boolean | undefined>(undefined);
  const [locked, setLocked] = useState(false), [overwrite, setOverwrite] = useState<boolean>();
  usePageRetention(!locked && overwrite === undefined, locked || overwrite !== undefined);
  const interaction = useCallback((value: boolean) => {rootLocked.current = value; setLocked(value);}, []);
  useEffect(() => {onBlockedChange?.(locked || overwrite !== undefined); return () => onBlockedChange?.(false);}, [locked, overwrite, onBlockedChange]);
  const closeSync = useCallback(() => {sync.current = undefined; setOverwrite(undefined);}, []);
  const customButtonEvents = useMemo(() => Object.fromEntries((['sync', 'overwrite'] as const).map(key => [key, () => {
    if (!operation.current() || !operation.isSelected() || rootLocked.current || sync.current !== undefined
      || permissions.error || !permissions.data?.buttons.some(button => button.key === key)) return;
    sync.current = key === 'overwrite'; setOverwrite(sync.current);
  }])), [operation.current, operation.isSelected, permissions.error, permissions.data]);
  const changed = () => {
    if (operation.current()) void client.invalidateQueries({queryKey: scopedQueryKey(scope, [])});
  };
  const committed = () => {
    closeSync();
    void message.success(t('tenant.resources.syncDone')); changed();
  };
  return <div className="tenant-resource-manager" style={{flex: 1, minHeight: 0}}>
    <SimpleTable<TenantResource> name="tenant.resources" baseUrl={endpoint} i18nNamespaces={['common', 'table']}
      columnOverrides={resourceAvailabilityColumns(t, locale)}
      storageKey="tenant-resource-manager" rowActions schemaStaleTime={30_000} confirmDiscard
      onInteractionLockChange={interaction} onReadAccessDenied={closeSync} customButtonEvents={customButtonEvents}
      initialValues={resourceDraft(null)}
      isButtonVisible={(button, rows) => rows.length === 0 || rows.every(row => row.allowedActions?.includes(button.key))}
      isButtonDisabled={() => sync.current !== undefined || !operation.selected}
      loadEditingRecord={(row, signal) => get<TenantResource>(`${endpoint}/${encodeURIComponent(row.id)}`, undefined, {signal})}
      renderForm={context => <ResourceEditor context={context} tenantId={tenantId}
        localizationSchema={permissions.data?.schema.properties?.nameLocalization as import('@rjsf/utils').RJSFSchema | undefined}/>}
      onSubmit={(action, data, current, context) => action === 'add'
        ? post(endpoint, resourcePayload(data), {signal: context.signal})
        : put(`${endpoint}/${encodeURIComponent(current.id)}`, resourcePayload(data), {signal: context.signal})}
      afterSubmit={changed}
      deleteRows={async (rows, signal) => {
        if (rows.length !== 1 || !rows[0].allowedActions?.includes('delete')) throw new Error(t('table.actionUnavailable'));
        await request(`${endpoint}/${encodeURIComponent(rows[0].id)}`, {method: 'DELETE', signal});
        if (!signal.aborted) changed();
      }}
      loadPage={async ({page, size, filters, sort, signal}) => normalizePage(await get<RawPage<TenantResource>>(endpoint,
        {page, size, ...filters, sort, ...(Object.keys(filters).length ? {} : {rootsOnly: true})}, {signal}), size)}
      tree={{hasChildren: row => row.type !== 'ACTION' && row.type !== 'API', loadChildrenPage: async (row, page, signal) => {
        const result = normalizePage(await get<RawPage<TenantResource>>(endpoint, {parentId: row.id, page, size: 20}, {signal}));
        return {content: result.content, hasNext: result.page.number + 1 < result.page.totalPages};
      }}}/>
    {overwrite !== undefined && <TenantResourceSync overwrite={overwrite} close={closeSync} committed={committed}/>}
  </div>;
}
