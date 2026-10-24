import {useCallback, useEffect, useState} from 'react';
import {Alert, Modal, Skeleton} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import {get, post} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useOwnedReads} from '@simplepoint/shared/hooks/useOwnedReads';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

const endpoint = '/common/tenants/current-resources';
type Preview = {added: number; refreshed: number; deactivated: number;
  additions: Array<{id: string; code: string; name: string}>; deactivations: Array<{id: string; code: string; name: string}>};

export default function TenantResourceSync({overwrite, close, committed}: {
  overwrite: boolean; close: () => void; committed: () => void;
}) {
  const {t} = useI18n();
  const operation = useOperationScope(String(overwrite));
  const {run, cancel, errors, loading, active} = useOwnedReads(operation, true);
  const [preview, setPreview] = useState<Preview>();
  const [writeError, setWriteError] = useState<string>();
  const load = useCallback(() => {
    setPreview(undefined);
    void run('preview', signal => get<Preview>(`${endpoint}/sync/preview`, {overwrite}, {signal}), setPreview);
  }, [run, overwrite]);
  useEffect(() => {if (active && operation.selected) load(); return () => cancel();}, [active, operation.selected, load, cancel]);
  const apply = async () => {
    if (!preview || loading.length || errors.preview) return;
    const request = operation.begin(); if (!request) return;
    setWriteError(undefined);
    try {
      // The preview is informative; execution reloads current definitions and has no resource version token.
      await post(`${endpoint}/sync?overwrite=${overwrite}`, {}, {signal: request.signal});
      if (operation.current(request.signal)) committed();
    } catch (error) {
      if (operation.current(request.signal)) setWriteError(resolveApiErrorMessage(error, t('tenant.resources.syncFailed')));
    } finally {operation.finish(request);}
  };
  return <Modal rootClassName="tenant-resource-confirmation" title={overwrite ? t('tenant.resources.restorePresentation') : t('tenant.resources.checkUpdates')}
    open={operation.selected} onCancel={() => {if (!operation.isBusy()) close();}} onOk={() => void apply()}
    confirmLoading={operation.busy} okButtonProps={{disabled: !preview || !!errors.preview || loading.length > 0}}
    cancelButtonProps={{disabled: operation.busy}} closable={!operation.busy} keyboard={!operation.busy} maskClosable={false}>
    <p>{overwrite ? t('tenant.resources.restorePresentationHint') : t('tenant.resources.checkUpdatesHint')}</p>
    {loading.length > 0 && <Skeleton active paragraph={{rows: 2}}/>}
    {!!errors.preview && <Alert type="error" showIcon title={resolveApiErrorMessage(errors.preview, t('tenant.resources.syncFailed'))}
      action={<TableActions buttons={[{key: 'retry', sort: 0, icon: 'ReloadOutlined', text: t('action.retry'), onClick: load}]} selectedKeys={[]} selectedRows={[]}/>}/>}
    {preview && <ol className="tenant-resource-sync-steps">
      <li>{t('tenant.resources.stepDiscover')}：+{preview.added} / ↻{preview.refreshed} / −{preview.deactivated}</li>
      <li>{t('tenant.resources.stepMap')}{preview.additions.length > 0 && <ul>{preview.additions.slice(0, 8).map(row => <li key={row.id}>{row.name} · {row.code}</li>)}</ul>}</li>
      <li>{t('tenant.resources.stepApply')}{preview.deactivations.length > 0 && <ul>{preview.deactivations.slice(0, 8).map(row => <li key={row.id}>{row.name} · {row.code}</li>)}</ul>}</li>
    </ol>}
    {writeError && <Alert type="error" showIcon title={writeError}/>}
  </Modal>;
}
