import {useEffect, useRef, useState} from 'react';
import {Alert, Modal, Space, Tag, Typography} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import {get, post} from '@simplepoint/shared/api/methods';
import {isHttpError, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';

type Lifecycle = {operationId?: string; pageId: string; generation: number; state: string;
  unfinishedSubmissions: number; requiredParticipants: string[]; completedParticipants: string[]};

/** The server schema authorizes opening this panel; every lifecycle API repeats that permission check. */
export default function ResourceLifecycle({applicationId, pageId, onClose, onChanged}: {
  applicationId: string; pageId: string; onClose: () => void; onChanged: () => void;
}) {
  const {t} = useI18n();
  const base = `/app/applications/${encodeURIComponent(applicationId)}/pages/${encodeURIComponent(pageId)}/resource-lifecycle`;
  const operation = useOperationScope(base);
  const [state, setState] = useState<Lifecycle>();
  const [error, setError] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const recoveryId = useRef<string | undefined>(undefined);
  const [intent, setIntent] = useState<{operationId: string; expectedGeneration: number}>();
  const intentRef = useRef(intent); intentRef.current = intent;
  usePageRetention(!operation.busy && !intent, operation.busy || Boolean(intent));
  const refresh = async () => {
    const controller = operation.begin(); if (!controller) return;
    try {
      const next = await get<Lifecycle>(base, undefined, {signal: controller.signal, notifyOnError: false});
      if (!operation.current(controller.signal)) return;
      setState(next); setError('');
      if (next.operationId === intentRef.current?.operationId || next.state === 'OFFLINE') setIntent(undefined);
      if (next.state === 'OFFLINE') onChanged();
    } catch (cause) {
      if (operation.current(controller.signal)) setError(resolveApiErrorMessage(cause, t('app.resource.loadFailed', '资源状态读取失败')));
    } finally {operation.finish(controller);}
  };
  const refreshRef = useRef(refresh); refreshRef.current = refresh;
  useEffect(() => {void refreshRef.current();}, [base]);
  useEffect(() => {
    if (state?.state !== 'OFFLINING' || operation.busy || !operation.selected) return;
    const timer = setTimeout(() => void refreshRef.current(), 2500);
    return () => clearTimeout(timer);
  }, [state, operation.busy, operation.selected]);
  const offline = async () => {
    if (!state || !operation.selected || state.unfinishedSubmissions > 0) return;
    const controller = operation.begin(); if (!controller) return;
    const request = intentRef.current ?? {operationId: crypto.randomUUID(), expectedGeneration: state.generation};
    intentRef.current = request; setIntent(request); setConfirmed(false); setError('');
    try {
      const next = await post<Lifecycle>(base + '/offline', request, {signal: controller.signal, notifyOnError: false});
      if (!operation.current(controller.signal)) return;
      setState(next); setIntent(undefined); intentRef.current = undefined; onChanged();
    } catch (cause) {
      if (operation.current(controller.signal)) {
        if (isHttpError(cause) && cause.status >= 400 && cause.status < 500) {
          setIntent(undefined); intentRef.current = undefined;
        }
        setError(resolveApiErrorMessage(cause, t('app.resource.offlineFailed', '下线结果尚未确认，请刷新状态或重试原操作。')));
      }
    } finally {operation.finish(controller);}
  };
  const recover = async () => {
    if (!state?.operationId) return;
    const controller = operation.begin(); if (!controller) return;
    recoveryId.current ??= crypto.randomUUID();
    try {
      const next = await post<Lifecycle>(base + '/retry', {operationId: state.operationId, requestId: recoveryId.current},
        {signal: controller.signal, notifyOnError: false});
      if (operation.current(controller.signal)) {setState(next); setError(''); recoveryId.current = undefined;}
    } catch (cause) {
      if (operation.current(controller.signal)) setError(resolveApiErrorMessage(cause, t('app.resource.recoveryFailed', '暂时无法继续下线，请稍后重试。')));
    } finally {operation.finish(controller);}
  };
  const label = state?.state === 'ONLINE' ? t('app.resource.online', '已上线')
    : state?.state === 'OFFLINING' ? t('app.resource.offlining', '下线中')
      : state?.state === 'OFFLINE' ? t('app.resource.offline', '已下线') : t('app.resource.unavailable', '尚未上线');
  return <Modal open={operation.selected} title={t('app.resource.lifecycle', '资源上下线')}
    onCancel={() => {if (!operation.isBusy() && !intent) onClose();}} closable={!operation.busy && !intent}
    maskClosable={!operation.busy && !intent} keyboard={!operation.busy && !intent}
    footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'refresh', sort: 0, icon: 'ReloadOutlined', text: t('app.resource.refresh', '刷新状态'), disabled: operation.busy},
      ...(state?.state === 'OFFLINING' ? [{key: 'recover', sort: 2, icon: 'ReloadOutlined', text: t('app.resource.recover', '继续下线'), disabled: operation.busy}] : []),
      ...(state?.state === 'ONLINE' || intent ? [{key: 'offline', sort: 1, icon: 'StopOutlined', danger: true,
        text: intent ? t('app.resource.retry', '重试原下线') : confirmed ? t('app.resource.confirmOffline', '确认下线并收回授权') : t('app.resource.takeOffline', '下线'),
        disabled: operation.busy || !state || state.unfinishedSubmissions > 0}] : []),
    ]} onButtonEvents={{recover: () => void recover(), refresh: () => void refresh(), offline: () => {
      if (confirmed || intent) void offline(); else setConfirmed(true);
    }}}/>}>
    <Space orientation="vertical" style={{width: '100%'}} size="middle">
      {error && <Alert type="error" showIcon title={error}/>}
      {state && <Tag color={state.state === 'ONLINE' ? 'green' : state.state === 'OFFLINING' ? 'processing' : 'default'}>{label}</Tag>}
      {state && state.unfinishedSubmissions > 0 && <Alert type="warning" showIcon
        title={t('app.resource.blocked', '还有 {count} 项未完成提交或流程，处理完成后才能下线。', {count: state.unfinishedSubmissions})}/>}
      {(confirmed || state?.state === 'OFFLINE') && <Alert type="info" showIcon
        title={t('app.resource.offlineEffect', '下线会关闭入口并收回相关授权，业务数据与历史保留。再次上线后需要重新授权。')}/>}
      {state?.state === 'OFFLINING' && <Typography.Text>
        {t('app.resource.progress', '已完成 {done}/{total} 个必需步骤；服务恢复后会继续处理。', {
          done: state.completedParticipants.length, total: state.requiredParticipants.length})}
      </Typography.Text>}
      {state?.state === 'OFFLINE' && <Typography.Text type="secondary">
        {t('app.resource.reonlineHint', '在表单设计器中重新发布到此页面即可再次上线。')}
      </Typography.Text>}
    </Space>
  </Modal>;
}
