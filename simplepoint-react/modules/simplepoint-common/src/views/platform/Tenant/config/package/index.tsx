import {useCallback, useEffect, useMemo, useRef, useState, type Key} from 'react';
import {Alert, App, Modal} from 'antd';
import {useQueryClient} from '@tanstack/react-query';
import SimpleTable from '@simplepoint/components/SimpleTable';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import ResourceDrawer from '@simplepoint/components/ResourceDrawer';
import PackageResources from '@simplepoint/components/TenantPackageResources';
import {usePackageColumns} from '@simplepoint/components/TenantPackageResources/usePackageColumns';
import {post} from '@simplepoint/shared/api/methods';
import {isHttpError, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import type {TenantPackage} from '@simplepoint/shared/types/tenantPackage';
import PackageSelection from './PackageSelection';

export interface TenantPackageConfigProps {
  tenantId: string;
  onInteractionLockChange?: (locked: boolean) => void;
}
type Allocation = {allocationId: string; packageCode: string};
type Selection = {action: 'allocate'; allocationId: string} | {action: 'resources' | 'revoke'; row: TenantPackage};
const widgets = {PackageSelection};

/** A pending allocation is a purchase receipt; only the tenant owner may activate its time. */
function PackageSession({tenantId, onInteractionLockChange}: TenantPackageConfigProps) {
  const baseUrl = `/common/platform/tenants/${encodeURIComponent(tenantId)}/package-instances`;
  const {t} = useI18n(), {message} = App.useApp();
  const operation = useOperationScope(tenantId), scope = useQueryScope(), client = useQueryClient();
  const permissions = useSchema(baseUrl, {retry: false, staleTime: 30_000});
  const [selection, setSelection] = useState<Selection>();
  const captured = useRef<Selection | undefined>(undefined), rootLocked = useRef(false);
  const [draft, setDraft] = useState<{packageCode?: string}>({});
  const [error, setError] = useState<string>();
  const [uncertain, setUncertain] = useState(false);
  const pending = useRef<Allocation | undefined>(undefined), uncertainRef = useRef(false);
  const form = useRef<SFormHandle>(null);
  const blocked = !!selection || operation.busy;
  usePageRetention(!blocked, blocked);
  useEffect(() => {onInteractionLockChange?.(blocked); return () => onInteractionLockChange?.(false);}, [blocked, onInteractionLockChange]);
  const reset = useCallback(() => {
    captured.current = undefined; pending.current = undefined; uncertainRef.current = false;
    setSelection(undefined); setDraft({}); setError(undefined); setUncertain(false);
  }, []);
  const close = useCallback(() => {
    if (!operation.current() || !operation.isSelected() || operation.isBusy() || uncertainRef.current) return;
    reset();
  }, [operation.current, operation.isSelected, operation.isBusy, reset]);
  const customButtonEvents = useMemo(() => Object.fromEntries((['allocate', 'resources', 'revoke'] as const).map(action => [action,
    (_keys: Key[], rows: TenantPackage[]) => {
      if (!operation.current() || !operation.isSelected() || operation.isBusy() || rootLocked.current || captured.current
        || permissions.error || !permissions.data?.buttons.some(button => button.key === action)
        || (action !== 'allocate' && (rows.length !== 1 || !rows[0].id || !rows[0].allowedActions?.includes(action)))) return;
      const next: Selection = action === 'allocate' ? {action, allocationId: crypto.randomUUID()} : {action, row: structuredClone(rows[0])};
      captured.current = next; setSelection(next); setDraft({}); setError(undefined);
    }])), [operation.current, operation.isSelected, operation.isBusy, permissions.data, permissions.error]);
  const submit = async (packageCode?: string) => {
    const selected = captured.current;
    if (!selected || selected.action === 'resources') return;
    const command = selected.action === 'allocate' ? pending.current ?? {allocationId: selected.allocationId, packageCode: packageCode ?? ''} : undefined;
    if (command && !command.packageCode) return;
    const request = operation.begin(); if (!request) return;
    if (command) pending.current = command;
    setError(undefined);
    try {
      await post(selected.action === 'allocate' ? baseUrl : `${baseUrl}/${encodeURIComponent(selected.row.id)}/revoke`,
        command ?? {}, {signal: request.signal});
      if (!operation.current(request.signal)) return;
      reset();
      void message.success(selected.action === 'allocate' ? t('tenant.packages.allocated') : t('tenant.packages.revoked'));
      await client.invalidateQueries({queryKey: scopedQueryKey(scope, [baseUrl])});
    } catch (failure) {
      if (operation.current(request.signal)) {
        if (!isHttpError(failure) || failure.status === 0 || failure.status >= 500) {
          uncertainRef.current = true; setUncertain(true);
        } else if (!uncertainRef.current) {
          pending.current = undefined;
        }
        setError(resolveApiErrorMessage(failure, t('tenant.packages.operationFailed')));
      }
    } finally {operation.finish(request);}
  };
  const columns = usePackageColumns();
  return <div style={{flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column'}}>
    <SimpleTable<TenantPackage> rowActions name="tenant.packages" baseUrl={baseUrl} i18nNamespaces={['common', 'table']}
      schemaStaleTime={30_000} customButtonEvents={customButtonEvents} onReadAccessDenied={reset}
      onInteractionLockChange={value => {rootLocked.current = value;}}
      isButtonVisible={(button, rows) => button.key === 'allocate' || rows.length === 0 || rows.every(row => row.allowedActions?.includes(button.key))}
      isButtonDisabled={(button, _keys, rows) => !!captured.current || operation.busy || !operation.selected
        || !!permissions.error || !permissions.data || (button.key !== 'allocate' && (rows.length !== 1 || !rows[0].allowedActions?.includes(button.key)))}
      columnOverrides={columns}/>    <ResourceDrawer title={t('tenant.packages.actions.resources')} open={selection?.action === 'resources'} onClose={close}>
      {selection?.action === 'resources' && <PackageResources key={selection.row.id} baseUrl={baseUrl} instanceId={selection.row.id}/>}
    </ResourceDrawer>
    {/* Native controls only confirm the domain command; list actions use the platform renderer. */}
    <Modal rootClassName="tenant-package-administration" title={selection?.action === 'allocate' ? t('tenant.packages.actions.allocate') : t('tenant.packages.actions.revoke')}
      open={!!selection && selection.action !== 'resources' && operation.selected} destroyOnHidden
      onCancel={close} onOk={() => {if (selection?.action === 'allocate' && !uncertain) form.current?.submit(); else void submit();}}
      confirmLoading={operation.busy} closable={!operation.busy && !uncertain} maskClosable={false} keyboard={!operation.busy && !uncertain}
      cancelButtonProps={{disabled: operation.busy || uncertain}} okButtonProps={{danger: selection?.action === 'revoke'}}
      okText={uncertain ? t('table.retry') : selection?.action === 'allocate' ? t('tenant.packages.actions.allocate') : t('tenant.packages.actions.revoke')}>
      {selection?.action === 'allocate' ? <>
        <Alert type="info" showIcon title={t('tenant.packages.allocationNotice')} style={{marginBottom: 12}}/>
        <SForm ref={form} schema={{type: 'object', required: ['packageCode'], properties: {packageCode: {type: 'string', minLength: 1, title: t('tenant.packages.packageName')}}}}
          uiSchema={{packageCode: {'ui:widget': 'PackageSelection'}}} widgets={widgets} formData={draft}
          hideSubmit disabled={operation.busy || uncertain} onChange={event => setDraft(event.formData ?? {})}
          onSubmit={event => void submit(event.formData?.packageCode)}/>
      </> : <><p>{selection?.action === 'revoke' ? selection.row.packageName || selection.row.packageCode : ''}</p>
        <Alert type="warning" showIcon title={t('tenant.packages.revocationNotice')}/></>}
      {uncertain && <Alert type="warning" showIcon title={t('tenant.packages.retryNotice')} style={{marginTop: 12}}/>}
      {error && <Alert type="error" showIcon title={error} style={{marginTop: 12}}/>}
    </Modal>
  </div>;
}

export default function TenantPackageConfig(props: TenantPackageConfigProps) {
  const scope = useQueryScope();
  return props.tenantId ? <PackageSession key={JSON.stringify([scope, props.tenantId])} {...props}/> : null;
}
