import {Alert, Spin} from 'antd';
import {useEffect} from 'react';
import {get} from '@simplepoint/shared/api/methods';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import FormRuntime from '../FormRuntime';
import TableActions from '../Table/Actions';
import type {SubmissionSnapshot} from './types';

/** Snapshot authorization is server-side; a failed refresh never leaves an actionable stale view. */
export default function SnapshotView({url, onValidityChange}: {url: string; onValidityChange?: (valid: boolean) => void}) {
  const {t} = useI18n();
  const query = useOwnedData(['app', 'submission-snapshot', url], ({signal}) =>
    get<SubmissionSnapshot>(url, undefined, {signal, notifyOnError: false}), {retry: false, staleTime: 0});
  const valid = !!query.data && !query.isError && !query.isFetching;
  useEffect(() => {onValidityChange?.(valid);}, [valid, onValidityChange]);
  if (query.isError) return <Alert type="error" showIcon
    title={resolveApiErrorMessage(query.error, t('app.workflow.snapshotFailed', '业务快照不可用'))}
    action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, icon: 'ReloadOutlined', text: t('app.retry', '重试')}]}
      onButtonEvents={{retry: () => void query.refetch()}}/>}/>;
  if (!query.data || query.isFetching) return <Spin/>;
  const {form, record} = query.data;
  return <FormRuntime key={JSON.stringify([url, form.formId, form.formVersion, record.id, record.version])}
    document={form.document} sceneId={form.sceneId} value={record.values}
    systemValues={record.systemValues} systemLabels={record.systemLabels} recordState={record.state} readOnly/>;
}
