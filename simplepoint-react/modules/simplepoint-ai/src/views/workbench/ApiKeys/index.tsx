import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {TableButtonProps} from '@simplepoint/components/Table';
import type {SimpleTableSubmitAction} from '@simplepoint/components/SimpleTable/types';
import TableActions from '@simplepoint/components/Table/Actions';
import {hasRecordIdentity} from '@simplepoint/components/SimpleTable/recordIdentity';
import {post, put} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {useQueryClient} from '@tanstack/react-query';
import {Alert, App, Modal, Space, Tag, Typography} from 'antd';
import {useCallback, useMemo, useState} from 'react';
import {apiKeyDate, apiKeyPolicyData, apiKeyEditorSchema, issuedKeyOf} from './model';
import {resourceScopeLabel} from '../modelLabels';
import {
  localizeWorkbenchError,
  resolveWorkbenchOperationError,
} from '../workbenchErrorCodes';

const {Paragraph, Text} = Typography;

type ApiKeyRow = {
  id: string;
  scopeType?: string;
  name?: string;
  keyPrefix?: string;
  enabled?: boolean;
  expiresAt?: string;
  lastUsedAt?: string;
  usageCount?: number;
  issuedKey?: string;
};

const apiKeyOperationFallback = {
  key: 'ai.api-keys.error.operationFailed',
  fallback: 'API Key 操作失败，请稍后重试',
};

const apiKeyRotationFallback = {
  key: 'ai.api-keys.rotate.failed',
  fallback: 'API Key 轮换失败',
};

const ApiKeysSession = () => {
  const baseConfig = api['ai-workbench.api-keys'];
  const {t} = useI18n();
  const {message} = App.useApp();
  const scope = useQueryScope(), queryClient = useQueryClient();
  const operation = useOperationScope();
  const permissions = useSchema(baseConfig.baseUrl, {retry: false, staleTime: 30_000});
  const granted = useCallback((key: string) => !permissions.error && Boolean(permissions.data?.buttons.some(button => button.key === key)), [permissions.error, permissions.data]);
  const [disclosure, setDisclosure] = useState<{key?: string}>();
  const [rotating, setRotating] = useState<ApiKeyRow>();
  const [rotateError, setRotateError] = useState('');
  const [formOpen, setFormOpen] = useState(false);
  const locked = operation.busy || !!rotating || !!disclosure;
  usePageRetention(!locked && !formOpen, locked || formOpen);
  const refresh = useCallback(() => {void queryClient.invalidateQueries({queryKey: scopedQueryKey(scope, [baseConfig.baseUrl, baseConfig.name, ''])});},
    [queryClient, scope, baseConfig.baseUrl, baseConfig.name]);

  const revealIssuedKey = useCallback((result: unknown) => {
    if (operation.current()) setDisclosure({key: issuedKeyOf(result)});
  }, [operation.current]);

  const rotate = useCallback((rows: ApiKeyRow[]) => {
    const row = rows[0];
    if (locked || formOpen || operation.isBusy() || !operation.isSelected() || !granted('rotate') || !hasRecordIdentity(row)) return;
    setRotating(structuredClone(row)); setRotateError('');
  }, [locked, formOpen, operation.isBusy, operation.isSelected, granted]);

  const confirmRotation = useCallback(async () => {
    if (!rotating || !granted('rotate') || disclosure || !hasRecordIdentity(rotating)) return;
    const request = operation.begin(); if (!request) return; setRotateError('');
    try {
      const result = await post<ApiKeyRow>(`${baseConfig.baseUrl}/${encodeURIComponent(rotating.id)}/rotate`, {},
        {signal: request.signal, notifyOnError: false});
      if (!operation.current(request.signal)) return;
      setRotating(undefined); revealIssuedKey(result); refresh();
      if (operation.isSelected()) message.success(t('ai.api-keys.rotate.success', 'API Key 已轮换'));
    } catch (error) {
      if (operation.current(request.signal)) setRotateError(localizeWorkbenchError(t, resolveWorkbenchOperationError(error, apiKeyRotationFallback)));
    } finally {operation.finish(request);}
  }, [rotating, granted, disclosure, operation.begin, operation.current, operation.finish, operation.isSelected, baseConfig.baseUrl, revealIssuedKey, refresh, message, t]);

  const submitPolicy = useCallback(async (action: SimpleTableSubmitAction, values: Record<string, unknown>, current: ApiKeyRow | null) => {
    if (locked || !granted(action) || action === 'edit' && !hasRecordIdentity(current)) throw new Error(t('table.actionUnavailable', '此操作暂不可用，请刷新页面后重试'));
    const request = operation.begin(); if (!request) throw new Error(t('table.actionUnavailable', '此操作暂不可用，请刷新页面后重试'));
    try {
      const data = apiKeyPolicyData(permissions.data?.schema, values);
      const result = action === 'edit'
        ? await put<ApiKeyRow>(baseConfig.baseUrl, {...data, id: current!.id}, {signal: request.signal, notifyOnError: false})
        : await post<ApiKeyRow>(baseConfig.baseUrl, data, {signal: request.signal, notifyOnError: false});
      if (!operation.current(request.signal)) return undefined;
      if (operation.isSelected()) message.success(action === 'edit' ? t('table.editSuccess', '修改成功') : t('table.addSuccess', '新增成功'));
      return result;
    } catch (error) {
      if (operation.current(request.signal)) throw error;
      return undefined;
    } finally {operation.finish(request);}
  }, [locked, granted, operation.begin, operation.current, operation.finish, operation.isSelected, permissions.data?.schema, baseConfig.baseUrl, message, t]);

  const customButtonEvents = useMemo<Record<string, (
    selectedRowKeys: React.Key[],
    selectedRows: ApiKeyRow[],
    props: TableButtonProps,
  ) => void>>(() => ({
    rotate: (_keys, rows) => rotate(rows),
  }), [rotate]);

  const formSchemaTransform = useCallback(apiKeyEditorSchema, []);

  const columnOverrides = useMemo(() => ({
    scopeType: {
      width: 105,
      render: (value: string) => (
        <Tag color={value === 'TENANT' ? 'blue' : value === 'SYSTEM' ? 'purple' : 'default'}>
          {value === 'TENANT'
            ? t('ai.api-keys.scope.tenant', '租户私有')
            : value === 'SYSTEM' ? t('ai.api-keys.scope.system', '系统共享') : resourceScopeLabel(t, value)}
        </Tag>
      ),
    },
    keyPrefix: {
      width: 175,
      render: (value: string) => value ? <Text code>{value}…</Text> : '-',
    },
    enabled: {
      width: 90,
      render: (value: boolean) => (
        <Tag color={value === true ? 'green' : 'default'}>
          {value === true
            ? t('ai.api-keys.status.enabled', '已启用')
            : value === false ? t('ai.api-keys.status.disabled', '已禁用') : '—'}
        </Tag>
      ),
    },
    expiresAt: {width: 175, render: apiKeyDate},
    lastUsedAt: {width: 175, render: apiKeyDate},
    usageCount: {width: 110},
  }), [t]);

  const errorMessageResolver = useCallback((error: unknown) => (
    localizeWorkbenchError(
      t,
      resolveWorkbenchOperationError(error, apiKeyOperationFallback),
    )
  ), [t]);

  return (
    <div className="ai-api-keys-page" style={{height: '100%', display: 'flex', flexDirection: 'column', gap: 12}}>
      <Alert
        type="info"
        closable
        showIcon
        message={t('ai.api-keys.guide.title', 'OpenAI / Anthropic 兼容模型 API')}
        description={(
          <Space direction="vertical" size={2}>
            <Text>
              {t('ai.api-keys.guide.openai', 'OpenAI：POST /ai/v1/chat/completions，使用 Authorization: Bearer <API_KEY>')}
            </Text>
            <Text>
              {t('ai.api-keys.guide.anthropic', 'Anthropic：POST /ai/v1/messages，使用 x-api-key: <API_KEY>')}
            </Text>
            <Text type="secondary">
              {t('ai.api-keys.guide.models', '可先调用 GET /ai/v1/models 获取当前 Key 可访问的模型 ID。')}
            </Text>
          </Space>
        )}
      />
      <div style={{flex: 1, minHeight: 0}}>
        <SimpleTable
          {...baseConfig}
          schemaStaleTime={30_000}
          drawerOpen={formOpen} onDrawerOpenChange={open => setFormOpen(open)} confirmDiscard
          isButtonDisabled={() => locked || !operation.selected || !!permissions.error || !permissions.data}
          onSubmit={submitPolicy}
          customButtonEvents={customButtonEvents}
          formSchemaTransform={formSchemaTransform}
          columnOverrides={columnOverrides}
          afterSubmit={({action, result}) => {if (action === 'add' && result != null) revealIssuedKey(result);}}
          errorMessageResolver={errorMessageResolver}
        />
      </div>
      <Modal wrapClassName="ai-api-key-rotate" open={!!rotating && operation.selected} destroyOnHidden={!rotating}
        title={t('ai.api-keys.rotate.title', '轮换 API Key')} confirmLoading={operation.busy}
        closable={!operation.busy} maskClosable={!operation.busy} keyboard={!operation.busy}
        cancelButtonProps={{disabled: operation.busy}} okButtonProps={{disabled: !granted('rotate')}}
        okText={t('ai.api-keys.rotate.ok', '确认轮换')} cancelText={t('ai.api-keys.rotate.cancel', '取消')}
        onCancel={() => {if (!operation.isBusy()) {setRotating(undefined); setRotateError('');}}} onOk={() => void confirmRotation()}>
        <Paragraph>{rotating?.name}</Paragraph>
        <Paragraph>{t('ai.api-keys.rotate.confirm', '轮换后旧 Key 会立即失效，确定继续吗？')}</Paragraph>
        <Text type="secondary">{t('ai.api-keys.rotate.policyUnchanged', '轮换只替换密钥，不改变启用状态、有效期或限流策略。')}</Text>
        {rotateError && <Alert type="error" showIcon title={rotateError}/>}
      </Modal>
      {/* Ant Modal memoizes closed content; unmount the portal itself so inactive/closed secrets leave the DOM immediately. */}
      {disclosure && operation.selected && <Modal wrapClassName="ai-api-key-secret" open destroyOnHidden
        title={t('ai.api-keys.secret.title', '请立即保存 API Key')} width={640} maskClosable={false} keyboard={false}
        onCancel={() => setDisclosure(undefined)} footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'close', sort: 0, type: 'primary', text: t('ai.api-keys.secret.close', '关闭'), onClick: () => setDisclosure(undefined)},
        ]}/>}>{operation.selected && disclosure && <Space direction="vertical" size={12} style={{width: '100%'}}>
          {disclosure.key ? <>
            <Text type="warning">{t('ai.api-keys.secret.once', '完整 Key 只显示这一次，关闭后无法再次查看。')}</Text>
            <Paragraph copyable={{text: disclosure.key}} code style={{wordBreak: 'break-all', marginBottom: 0}}>{disclosure.key}</Paragraph>
          </> : <Alert type="warning" showIcon title={t('ai.api-keys.secret.unavailable', '操作已完成，但未取得可显示的密钥。请检查当前权限，必要时再次轮换。')}/>}
        </Space>}</Modal>}
    </div>
  );
};

export default function ApiKeys() {
  const scope = useQueryScope();
  return <ApiKeysSession key={JSON.stringify(scope)}/>;
}
