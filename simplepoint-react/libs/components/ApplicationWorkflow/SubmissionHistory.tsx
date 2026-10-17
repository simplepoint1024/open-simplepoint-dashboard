import {Alert, Modal, Spin} from 'antd';
import {lazy, Suspense, useEffect, useState} from 'react';
import {get, post} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import SimpleTable from '../SimpleTable';
import SnapshotView from './SnapshotView';
import SubmissionDialog from './SubmissionDialog';
import {submissionUrl, type Submission} from './types';

const Progress = lazy(() => import('../WorkflowRuntime/ProgressDrawer'));
type Row = {id: string; processInstanceId?: string; canSnapshot: boolean; canRetry: boolean; canResubmit: boolean};

/** Standard server-schema table; recovery always resumes the existing accepted intent. */
export default function SubmissionHistory({applicationId, pageId, onInteractionLockChange}: {
  applicationId: string; pageId: string; onInteractionLockChange?: (locked: boolean) => void;
}) {
  const {t} = useI18n(), selected = usePageSelected(), operation = useOperationScope(`${applicationId}:${pageId}`);
  const [snapshot, setSnapshot] = useState<string>(), [progress, setProgress] = useState<string>();
  const [resubmit, setResubmit] = useState<Submission>();
  const [revision, setRevision] = useState(0), [error, setError] = useState('');
  const [tableLocked, setTableLocked] = useState(false);
  useEffect(() => {onInteractionLockChange?.(tableLocked || operation.busy || !!snapshot || !!progress || !!resubmit);},
    [tableLocked, operation.busy, snapshot, progress, resubmit, onInteractionLockChange]);
  const url = submissionUrl(applicationId, pageId);
  const recover = async (id: string, rework: boolean) => {
    const request = operation.begin(); if (!request) return;
    setError('');
    try {
      const result = rework
        ? await get<Submission>(`${url}/${encodeURIComponent(id)}`, undefined, {signal: request.signal, notifyOnError: false})
        : await post<Submission>(`${url}/${encodeURIComponent(id)}/retry`, {}, {signal: request.signal, notifyOnError: false});
      if (!operation.current(request.signal)) return;
      if (rework) setResubmit(result); else setRevision(value => value + 1);
    } catch (cause) {
      if (operation.current(request.signal)) setError(resolveApiErrorMessage(cause, t('app.submission.failed', '提交未完成')));
    } finally {operation.finish(request);}
  };
  return <>
    {error && <Alert showIcon type="error" title={error}/>}
    <SimpleTable<Row> key={revision} name={`app.submissions.${pageId}`} baseUrl={url} i18nNamespaces={['app-workbench', 'workflow']}
      onInteractionLockChange={setTableLocked}
      columnOverrides={{id: {hidden: true}, processInstanceId: {hidden: true}, canSnapshot: {hidden: true}, canRetry: {hidden: true}, canResubmit: {hidden: true}}}
      isButtonDisabled={(button, _keys, rows) => operation.busy || !!snapshot || !!progress || !!resubmit || rows.length !== 1
        || button.key === 'snapshot' && !rows[0].canSnapshot || button.key === 'progress' && !rows[0].processInstanceId
        || button.key === 'retry' && !rows[0].canRetry || button.key === 'resubmit' && !rows[0].canResubmit}
      customButtonEvents={{snapshot: (_keys, rows) => setSnapshot(rows[0].id), progress: (_keys, rows) => setProgress(rows[0].processInstanceId),
        retry: (_keys, rows) => void recover(rows[0].id, false), resubmit: (_keys, rows) => void recover(rows[0].id, true)}}/>
    <Modal open={!!snapshot && selected} width="min(1050px, 94vw)" footer={null} destroyOnHidden
      title={t('app.submission.snapshot', '业务快照')} onCancel={() => setSnapshot(undefined)}>
      {snapshot && <SnapshotView url={`${url}/${encodeURIComponent(snapshot)}/snapshot`}/>}
    </Modal>
    {progress && <Suspense fallback={<Spin/>}><Progress id={progress} onClose={() => setProgress(undefined)}/></Suspense>}
    {resubmit && <SubmissionDialog applicationId={applicationId} pageId={pageId} previousSubmissionId={resubmit.id}
      definition={{id: resubmit.definitionId, version: resubmit.definitionVersion}}
      onAccepted={() => setRevision(value => value + 1)} onClose={() => setResubmit(undefined)}/>}
  </>;
}
