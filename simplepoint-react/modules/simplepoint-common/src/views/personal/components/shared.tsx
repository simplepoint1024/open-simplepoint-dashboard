import {useEffect, useRef, useState} from 'react';
import {Alert, Button, Tag} from 'antd';
import {useQueryClient} from '@tanstack/react-query';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {getQueryScopeSnapshot, scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import './personal.css';

export function usePersonalLabels() {
  const {t, ensure, locale} = useI18n();
  useEffect(() => { void ensure(['personal', 'common']); }, [ensure, locale]);
  const statuses = {
    TODO: t('personal.notStarted', '未开始'), IN_PROGRESS: t('personal.inProgress', '进行中'),
    DONE: t('personal.done', '已完成'), CANCELLED: t('personal.cancelled', '已取消'),
    PENDING: t('personal.pending', '待处理'),
  };
  const actions = {
    START: t('personal.start', '开始'), COMPLETE: t('personal.complete', '完成'),
    CANCEL: t('personal.cancelItem', '取消事项'), REOPEN: t('personal.reopen', '重新打开'),
    ARCHIVE: t('personal.archive', '归档'), UNARCHIVE: t('personal.unarchive', '移出归档'),
    DELETE: t('personal.delete', '移至回收站'), RESTORE: t('personal.restore', '恢复'),
    COMMENT: t('personal.comment', '添加备注'), CREATED: t('personal.created', '创建'),
    UPDATED: t('personal.updated', '编辑'), READ: t('personal.read', '标为已读'),
    UNREAD: t('personal.unread', '标为未读'),
  };
  const priorities = [t('personal.low', '低'), t('personal.normal', '普通'), t('personal.high', '高'), t('personal.urgent', '紧急')];
  return {t, locale, statuses, actions, priorities};
}

export function Status({status}: {status: string}) {
  const {statuses} = usePersonalLabels();
  return <Tag color={({TODO: 'default', IN_PROGRESS: 'processing', DONE: 'success', CANCELLED: 'default', PENDING: 'warning'} as Record<string, string>)[status]}>
    {statuses[status as keyof typeof statuses] || status}</Tag>;
}
export function Priority({value}: {value: number}) {
  const {priorities} = usePersonalLabels();
  return <Tag color={['default', 'blue', 'orange', 'red'][value]}>{priorities[value]}</Tag>;
}
export function formatTime(value?: string | null) { return value ? new Date(value).toLocaleString() : '—'; }
export function ErrorNotice({error, retry}: {error: unknown; retry?: () => void}) {
  const {t} = usePersonalLabels();
  if (!error) return null;
  return <Alert type="error" showIcon title={resolveApiErrorMessage(error, t('personal.failed', '操作失败，请重试'))}
    action={retry && <Button onClick={retry}>{t('personal.retry', '重试')}</Button>} />;
}

/** Scope-bound, abortable single-flight writes; failures never discard the user's draft. */
export function usePersonalWrite() {
  const client = useQueryClient();
  const scope = useQueryScope();
  const request = useRef<AbortController | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => () => { request.current?.abort(); }, [scope]);
  async function run<T>(operation: (signal: AbortSignal) => Promise<T>, propagate = false): Promise<T | undefined> {
    if (request.current || JSON.stringify(scope) !== getQueryScopeSnapshot()) return undefined;
    const controller = new AbortController();
    request.current = controller; setBusy(true); setError(null);
    try {
      const result = await operation(controller.signal);
      if (controller.signal.aborted || JSON.stringify(scope) !== getQueryScopeSnapshot()) return undefined;
      await Promise.all([['personal'], ...['items', 'todos', 'messages'].map(kind => [`/common/personal/${kind}`])]
        .map(key => client.invalidateQueries({queryKey: scopedQueryKey(scope, key)})));
      return result;
    } catch (cause) {
      if (!controller.signal.aborted) setError(cause);
      if (propagate) throw cause;
      return undefined;
    } finally {
      request.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  }
  return {run, busy, error, clearError: () => setError(null)};
}
