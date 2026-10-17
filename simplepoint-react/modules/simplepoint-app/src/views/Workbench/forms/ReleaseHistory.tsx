import {useEffect, useRef, useState} from 'react';
import {Alert, Drawer} from 'antd';
import {useQueryClient} from '@tanstack/react-query';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {get, post} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import ResourceLifecycle from './ResourceLifecycle';
import PublicationInspection from './PublicationInspection';
import {releaseIsActive, releaseIsCancelled, releaseIsBlocked, type PublicationInspection as Inspection, type FormRelease} from './publication';

type ReleaseRow = {id: string; pageId: string; pageName: string; formVersion: number; status: string; canCancel?: boolean; errorCode?: string};

/** Server-owned publication history. Recovery never freezes the current editable draft. */
export default function ReleaseHistory({applicationId, formId, onClose, onViewData}: {
  applicationId: string; formId: string; onClose: () => void; onViewData: (pageId: string) => void;
}) {
  const {t} = useI18n(), queries = useQueryClient(), scope = useQueryScope();
  const baseUrl = `/app/applications/${encodeURIComponent(applicationId)}/forms/${encodeURIComponent(formId)}/releases`;
  const operation = useOperationScope(baseUrl);
  const [lifecyclePage, setLifecyclePage] = useState<string>();
  const [error, setError] = useState(''), [complete, setComplete] = useState(false);
  const [pending, setPending] = useState<{row: ReleaseRow; action: 'resume' | 'cancel'}>();
  const [cancelled, setCancelled] = useState(false);
  const [inspection, setInspection] = useState<Inspection | null>();
  usePageRetention(!operation.busy && !pending, operation.busy || Boolean(pending));
  const run = async (row?: ReleaseRow, action: 'resume' | 'cancel' = 'resume') => {
    if (!row || ['ACTIVE', 'RETIRED', 'CANCELLED'].includes(row.status) || action === 'cancel' && !row.canCancel) return;
    const controller = operation.begin(); if (!controller) return;
    setError(''); setComplete(false); setCancelled(false); setInspection(undefined);
    try {
      const result = await post<FormRelease>(`${baseUrl}/${encodeURIComponent(row.id)}/${action}`, {},
        {signal: controller.signal, notifyOnError: false});
      if (!operation.current(controller.signal)) return;
      if (result.pageBinding?.pageId !== row.pageId || result.pageBinding.formId !== formId
        || result.pageBinding.formVersion !== row.formVersion) throw new Error('Unexpected publication recovery result');
      const active = releaseIsActive(result);
      const blocked = releaseIsBlocked(result);
      setInspection(result.inspection);
      const terminalFailure = !blocked && !releaseIsCancelled(result) && (['FAILED', 'RETIRED'].includes(result.pageBinding.status)
        || result.dataPublication?.status === 'FAILED');
      setCancelled(releaseIsCancelled(result));
      setComplete(active); setPending(active || terminalFailure || blocked || releaseIsCancelled(result) ? undefined : {row, action});
      if (terminalFailure) setError(t('app.publication.failed', '发布未完成，可重试本次发布。'));
      await queries.invalidateQueries({queryKey: scopedQueryKey(scope, [baseUrl])});
    } catch (cause) {
      if (!operation.current(controller.signal)) return;
      setPending(undefined);
      setError(resolveApiErrorMessage(cause, t('app.publication.failed', '发布未完成，可重试本次发布。')));
      void queries.invalidateQueries({queryKey: scopedQueryKey(scope, [baseUrl])});
    } finally {operation.finish(controller);}
  };
  const inspect = async (row?: ReleaseRow) => {
    if (!row) return;
    const controller = operation.begin(); if (!controller) return;
    setError(''); setInspection(undefined);
    try {
      const result = await get<Inspection | null>(`${baseUrl}/${encodeURIComponent(row.id)}/inspection`, undefined,
        {signal: controller.signal, notifyOnError: false});
      if (operation.current(controller.signal)) setInspection(result);
    } catch (cause) {
      if (operation.current(controller.signal)) setError(resolveApiErrorMessage(cause, t('app.publication.inspectionFailed', '发布检查结果暂时无法读取。')));
    } finally {operation.finish(controller);}
  };
  const runRef = useRef(run); runRef.current = run;
  useEffect(() => {
    if (!pending || operation.busy || !operation.selected) return;
    const timer = setTimeout(() => void runRef.current(pending.row, pending.action), 3000);
    return () => clearTimeout(timer);
  }, [pending, operation.busy, operation.selected]);
  return <Drawer rootClassName="app-form-release-history" open={operation.selected} width="80%"
    title={t('app.release.title', '发布记录')} onClose={() => {if (!operation.isBusy()) onClose();}}
    closable={!operation.busy} maskClosable={!operation.busy} keyboard={!operation.busy}>
    {error && <Alert type="error" showIcon title={error}/>}
    {pending && <Alert type="info" showIcon title={pending.action === 'cancel' ? t('app.release.cancelling', '正在确认取消结果，请稍候。关闭窗口不会撤销本次操作。')
      : t('app.release.recovering', '正在继续原发布。完成后会更新记录，关闭窗口不会丢失发布信息。')}/>}
    {cancelled && <Alert type="info" showIcon title={t('app.publication.cancelled', '本次发布已取消，现有页面保持不变。')}/>}
    {complete && <Alert type="success" showIcon title={t('app.publication.success', '表单已发布，可以开始使用。')}/>}
    <PublicationInspection inspection={inspection} onViewData={pageId => {if (!operation.isBusy()) {onClose(); onViewData(pageId);}}}/>
    {inspection === null && <Alert type="info" showIcon title={t('app.publication.inspectionClear', '此发布当前没有待处理的检查结果。')}/>}
    {lifecyclePage && <ResourceLifecycle applicationId={applicationId} pageId={lifecyclePage}
      onClose={() => setLifecyclePage(undefined)} onChanged={() => {void queries.invalidateQueries({queryKey: scopedQueryKey(scope, [baseUrl])});}}/>}
    <SimpleTable<ReleaseRow> name={`app.form.releases.${formId}`} baseUrl={baseUrl}
      storageKey="app.form.releaseHistory" i18nNamespaces={['app-workbench']}
      isButtonDisabled={(button, _, rows) => operation.busy || Boolean(pending) || !operation.selected || rows.length !== 1
        || (button.key === 'lifecycle' ? !['ACTIVE', 'RETIRED'].includes(rows[0].status) : button.key === 'inspection' ? rows[0].errorCode !== 'PUBLICATION_DATA_CONFLICT' : button.key === 'cancel' ? !rows[0].canCancel : button.key === 'resume' ? ['ACTIVE', 'RETIRED', 'CANCELLED'].includes(rows[0].status) : rows[0].status !== 'ACTIVE')}
      customButtonEvents={{lifecycle: (_, rows) => {if (rows[0]) setLifecyclePage(rows[0].pageId);}, inspection: (_, rows) => void inspect(rows[0]), resume: (_, rows) => void run(rows[0]), cancel: (_, rows) => void run(rows[0], 'cancel'), data: (_, rows) => {
        if (!operation.isBusy() && rows[0]?.status === 'ACTIVE') {onClose(); onViewData(rows[0].pageId);}
      }}}/>
  </Drawer>;
}
