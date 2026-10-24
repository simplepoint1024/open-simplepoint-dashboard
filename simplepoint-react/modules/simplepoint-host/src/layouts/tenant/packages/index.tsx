import {useCallback, useEffect, useMemo, useRef, useState, type Key} from 'react';
import {Alert, App, Modal} from 'antd';
import {useQueryClient} from '@tanstack/react-query';
import SimpleTable from '@simplepoint/components/SimpleTable';
import ResourceDrawer from '@simplepoint/components/ResourceDrawer';
import {usePackageColumns} from '@simplepoint/components/TenantPackageResources/usePackageColumns';
import {post} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {packageEndpoint, type TenantPackage} from './types';
import PackageResources from '@simplepoint/components/TenantPackageResources';

type Selection = {action: 'resources' | 'activate'; row: TenantPackage};

export default function TenantPackages({onBlockedChange}: {onBlockedChange?: (value: boolean) => void}) {
  const {t} = useI18n(), {message} = App.useApp();
  const operation = useOperationScope();
  const scope = useQueryScope(), client = useQueryClient();
  const permissions = useSchema(packageEndpoint, {retry: false, staleTime: 30_000});
  const [selection, setSelection] = useState<Selection>();
  const captured = useRef<Selection | undefined>(undefined), rootLocked = useRef(false);
  const [error, setError] = useState<string>();
  const blocked = !!selection || operation.busy;
  usePageRetention(!blocked, blocked);
  useEffect(() => {onBlockedChange?.(blocked); return () => onBlockedChange?.(false);}, [blocked, onBlockedChange]);
  const close = useCallback(() => {
    if (!operation.current() || operation.isBusy() || !operation.isSelected()) return;
    captured.current = undefined; setSelection(undefined); setError(undefined);
  }, [operation.current, operation.isBusy, operation.isSelected]);
  const denied = useCallback(() => {
    captured.current = undefined; setSelection(undefined); setError(undefined);
  }, []);
  const customButtonEvents = useMemo(() => Object.fromEntries((['resources', 'activate'] as const).map(action => [action,
    (_keys: Key[], rows: TenantPackage[]) => {
      if (!operation.current() || !operation.isSelected() || operation.isBusy() || rootLocked.current || captured.current
        || permissions.error || !permissions.data?.buttons.some(button => button.key === action)
        || rows.length !== 1 || !rows[0].id || !rows[0].allowedActions?.includes(action)) return;
      const next = {action, row: structuredClone(rows[0])};
      captured.current = next; setSelection(next); setError(undefined);
    }])), [operation.current, operation.isSelected, operation.isBusy, permissions.data, permissions.error]);
  const activate = async () => {
    const selected = captured.current;
    if (selected?.action !== 'activate' || !selected.row.allowedActions?.includes('activate')) return;
    const request = operation.begin(); if (!request) return;
    setError(undefined);
    try {
      await post(`${packageEndpoint}/${encodeURIComponent(selected.row.id)}/activate`, {}, {signal: request.signal});
      if (!operation.current(request.signal)) return;
      captured.current = undefined; setSelection(undefined);
      void message.success(t('tenant.packages.activated'));
      await client.invalidateQueries({queryKey: scopedQueryKey(scope, [])});
    } catch (failure) {
      if (operation.current(request.signal)) setError(resolveApiErrorMessage(failure, t('tenant.packages.activationFailed')));
    } finally {operation.finish(request);}
  };
  const columns = usePackageColumns();
  return <div className="tenant-packages" style={{flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column'}}>
    <SimpleTable<TenantPackage> rowActions name="tenant.packages" baseUrl={packageEndpoint} i18nNamespaces={['common', 'table']}
      schemaStaleTime={30_000} customButtonEvents={customButtonEvents}
      onInteractionLockChange={value => {rootLocked.current = value;}} onReadAccessDenied={denied}
      isButtonVisible={(button, rows) => rows.length === 0 || rows.every(row => row.allowedActions?.includes(button.key))}
      isButtonDisabled={(button, _keys, rows) => !!captured.current || operation.busy || !operation.selected
        || !!permissions.error || !permissions.data || rows.length !== 1 || !rows[0].allowedActions?.includes(button.key)}
      columnOverrides={columns}/>    <ResourceDrawer title={t('tenant.packages.actions.resources')} open={selection?.action === 'resources'} onClose={close}>
      {selection?.action === 'resources' && <PackageResources key={selection.row.id} baseUrl={packageEndpoint} instanceId={selection.row.id}/>}
    </ResourceDrawer>
    {/* Confirmation controls are owned by the platform dialog, not a second CRUD/action toolbar. */}
    <Modal rootClassName="tenant-package-activation" title={t('tenant.packages.actions.activate')}
      open={selection?.action === 'activate' && operation.selected} destroyOnHidden
      onCancel={close} onOk={() => void activate()} confirmLoading={operation.busy}
      closable={!operation.busy} maskClosable={false} keyboard={!operation.busy}
      cancelButtonProps={{disabled: operation.busy}} okText={t('tenant.packages.actions.activate')}>
      <p>{selection?.row.packageName || selection?.row.packageCode}</p>
      <Alert type="info" showIcon title={t('tenant.packages.activationNotice')}/>
      {error && <Alert type="error" showIcon title={error} style={{marginTop: 12}}/>}
    </Modal>
  </div>;
}
