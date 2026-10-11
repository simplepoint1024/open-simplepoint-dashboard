import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {SimpleTableSubmitAction} from '@simplepoint/components/SimpleTable/types';
import DataTable from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import type {TableButtonProps} from '@simplepoint/components/Table';
import {get, post, put} from '@simplepoint/shared/api/methods';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useOwnedReads} from '@simplepoint/shared/hooks/useOwnedReads';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {useQueryClient} from '@tanstack/react-query';
import {Alert, App, Descriptions, Modal, Tag, Typography} from 'antd';
import {useCallback, useMemo, useRef, useState} from 'react';
import {modelTypeLabel, resourceScopeLabel} from '../modelLabels';
import {resolveProviderMessage, resolveProviderOperationError, resolveProviderStatus} from './messageCodes';
import {discoveredModels, providerEditorSchema, providerIdentity, providerInput, providerSyncResult,
  providerTarget, providerTestResult, type DiscoveredModel, type ProviderAction, type ProviderRow,
  type ProviderTarget, type SyncResult, type TestResult} from './model';

const providerVendorLabels: Record<string, string> = {
  OPENAI: 'OpenAI',
  ANTHROPIC: 'Anthropic',
  GOOGLE_GEMINI: 'Google Gemini',
  AZURE_OPENAI: 'Azure OpenAI',
  MISTRAL: 'Mistral AI',
  GROQ: 'Groq',
  DEEPSEEK: 'DeepSeek',
  XAI: 'xAI',
  OPENROUTER: 'OpenRouter',
  TOGETHER_AI: 'Together AI',
  FIREWORKS_AI: 'Fireworks AI',
  ALIBABA_QWEN: 'Alibaba Qwen / DashScope',
  MOONSHOT: 'Moonshot / Kimi',
  MINIMAX: 'MiniMax',
  STEPFUN: 'StepFun',
  ZHIPU_AI: 'Zhipu AI / GLM',
  BAIDU_QIANFAN: 'Baidu Qianfan',
  TENCENT_HUNYUAN: 'Tencent Hunyuan',
  VOLCENGINE_DOUBAO: 'Volcengine Doubao',
  SILICONFLOW: 'SiliconFlow',
  NVIDIA_NIM: 'NVIDIA NIM',
  HUGGING_FACE: 'Hugging Face',
  COHERE: 'Cohere',
  OLLAMA: 'Ollama',
  LM_STUDIO: 'LM Studio',
  VLLM: 'vLLM',
  CUSTOM: 'Custom',
};


const baseConfig = api['ai-workbench.providers'];
const modelConfig = api['ai-workbench.models'];
const discoveryChannel = 'discovery:';
type ActionOwner = {action: ProviderAction; target: ProviderTarget};

function ProvidersSession() {
  const {t} = useI18n(), {message} = App.useApp();
  const scope = useQueryScope(), queryClient = useQueryClient();
  const operation = useOperationScope(baseConfig.baseUrl), reads = useOwnedReads(operation, true);
  const permissions = useSchema(baseConfig.baseUrl, {staleTime: 30_000, retry: false});
  const granted = useCallback((key: string) => !permissions.error && !!permissions.data?.buttons.some(button => button.key === key),
    [permissions.error, permissions.data]);
  const [owner, setOwner] = useState<ActionOwner>(), ownerRef = useRef<ActionOwner | undefined>(undefined);
  const [result, setResult] = useState<TestResult | SyncResult | DiscoveredModel[]>();
  const [actionError, setActionError] = useState('');
  const [attempted, setAttempted] = useState(false);
  const [rootLocked, setRootLocked] = useState(false), rootLockRef = useRef(false);
  const blocked = !!owner || operation.busy || rootLocked;
  usePageRetention(!blocked, blocked);
  const rootInteraction = useCallback((locked: boolean) => {rootLockRef.current = locked; setRootLocked(locked);}, []);
  const invalidResponse = t('ai.providers.page.operation.invalidResponse', '返回结果无效，请刷新状态后重试。');
  const errorText = useCallback((error: unknown) => {
    if (error instanceof Error && error.message === invalidResponse) return invalidResponse;
    const descriptor = resolveProviderOperationError(error);
    return t(descriptor.key, descriptor.fallback);
  }, [t, invalidResponse]);
  const refreshStatus = useCallback((action: ProviderAction) => {
    void queryClient.invalidateQueries({queryKey: scopedQueryKey(scope, [baseConfig.baseUrl, baseConfig.name, ''])});
    if (action === 'sync') void queryClient.invalidateQueries({queryKey: scopedQueryKey(scope, [modelConfig.baseUrl, modelConfig.name, ''])});
  }, [queryClient, scope]);
  const run = useCallback((captured: ActionOwner) => {
    if (!operation.current() || !operation.isSelected() || operation.isBusy() || reads.isBusy()
      || rootLockRef.current || ownerRef.current !== captured || !granted(captured.action)) return;
    setResult(undefined); setActionError('');
    if (captured.action === 'discover') {
      if (!reads.active) return;
      reads.clearErrors(discoveryChannel); setAttempted(true);
      void reads.run(discoveryChannel, async signal => {
        const response = await get<unknown>(`${baseConfig.baseUrl}/${encodeURIComponent(captured.target.id)}/models/discover`,
          undefined, {signal, notifyOnError: false});
        const models = discoveredModels(response);
        if (!models) throw new Error(invalidResponse);
        return models;
      }, models => {if (ownerRef.current === captured) setResult(models);});
      return;
    }
    const request = operation.begin(); if (!request) return;
    setAttempted(true);
    void (async () => {
      try {
        const suffix = captured.action === 'test' ? 'test' : 'models/sync';
        const response = await post<unknown>(`${baseConfig.baseUrl}/${encodeURIComponent(captured.target.id)}/${suffix}`, {},
          {signal: request.signal, notifyOnError: false});
        if (!operation.current(request.signal) || ownerRef.current !== captured) return;
        const parsed = captured.action === 'test' ? providerTestResult(response, captured.target.id) : providerSyncResult(response, captured.target.id);
        if (!parsed) throw new Error(invalidResponse);
        setResult(parsed);
      } catch (error) {
        if (operation.current(request.signal) && ownerRef.current === captured) setActionError(errorText(error));
      } finally {
        // Test and sync both persist provider status even when the remote operation fails.
        if (operation.current(request.signal)) refreshStatus(captured.action);
        operation.finish(request);
      }
    })();
  }, [operation.current, operation.isSelected, operation.isBusy, operation.begin, operation.finish,
    reads.isBusy, reads.active, reads.clearErrors, reads.run, granted, invalidResponse, errorText, refreshStatus]);
  const start = useCallback((action: ProviderAction, rows: ProviderRow[]) => {
    if (!operation.current() || !operation.isSelected() || !reads.active || operation.isBusy() || reads.isBusy()
      || rootLockRef.current || ownerRef.current || !granted(action) || rows.length !== 1) return;
    const target = providerTarget(rows[0]); if (!target) return;
    const captured = {action, target}; ownerRef.current = captured; setOwner(captured);
    setResult(undefined); setActionError(''); setAttempted(false); reads.clearErrors(discoveryChannel);
    if (action !== 'sync') run(captured);
  }, [operation.current, operation.isSelected, operation.isBusy, reads.active, reads.isBusy, reads.clearErrors, granted, run]);
  const close = () => {
    if (!operation.current() || !operation.isSelected() || operation.isBusy()) return;
    ownerRef.current = undefined; reads.cancel(discoveryChannel); reads.clearErrors(discoveryChannel);
    setOwner(undefined); setResult(undefined); setActionError(''); setAttempted(false);
  };
  const customButtonEvents = useMemo<Record<string, (
    keys: React.Key[], rows: ProviderRow[], button: TableButtonProps,
  ) => void>>(() => ({
    test: (_keys, rows) => start('test', rows),
    discover: (_keys, rows) => start('discover', rows),
    sync: (_keys, rows) => start('sync', rows),
  }), [start]);
  const isButtonDisabled = useCallback((button: TableButtonProps, _keys: React.Key[], rows: ProviderRow[]) =>
    !operation.selected || !!ownerRef.current || operation.isBusy() || rootLockRef.current || !granted(String(button.key))
      || button.key !== 'add' && rows.some(row => ['test', 'discover', 'sync'].includes(String(button.key)) ? !providerTarget(row) : !providerIdentity(row)),
  [operation.selected, operation.isBusy, granted, owner, rootLocked]);
  const submit = useCallback(async (action: SimpleTableSubmitAction, values: Record<string, unknown>, current: ProviderRow | null) => {
    const id = providerIdentity(current);
    const unavailable = () => new Error(t('table.actionUnavailable', '此操作暂不可用，请刷新页面后重试'));
    if (ownerRef.current || reads.isBusy() || !granted(action) || action === 'edit' && !id) throw unavailable();
    const request = operation.begin(); if (!request) throw unavailable();
    try {
      const data = providerInput(permissions.data?.schema, values, action === 'edit');
      const updated = action === 'edit'
        ? await put<ProviderRow>(baseConfig.baseUrl, {...data, id}, {signal: request.signal, notifyOnError: false})
        : await post<ProviderRow>(baseConfig.baseUrl, data, {signal: request.signal, notifyOnError: false});
      if (!operation.current(request.signal)) return undefined;
      if (operation.isSelected()) message.success(action === 'edit' ? t('table.editSuccess', '修改成功') : t('table.addSuccess', '新增成功'));
      return updated;
    } catch (error) {if (operation.current(request.signal)) throw error; return undefined;}
    finally {operation.finish(request);}
  }, [reads.isBusy, granted, operation.begin, operation.current, operation.isSelected, operation.finish, permissions.data?.schema, message, t]);
  const formSchemaTransform = useCallback((schema: any, record: ProviderRow | null) => providerEditorSchema(schema, record, providerVendorLabels,
    t('ai.providers.page.form.apiKey.description', '编辑时留空将保留已有凭证。凭证只写入，不会从服务端回传。')), [t]);
  const columnOverrides = useMemo(() => ({
    scopeType: {width: 110, render: (value: string) => <Tag color={value === 'TENANT' ? 'blue' : value === 'SYSTEM' ? 'purple' : 'default'}>{resourceScopeLabel(t, value)}</Tag>},
    vendor: {width: 190, render: (value: string) => providerVendorLabels[value] || t('ai.providers.vendor.unknown', '未知模型厂商')},
    baseUrl: {width: 300, ellipsis: true}, modelDiscoveryUrl: {width: 320, ellipsis: true},
    enabled: {width: 100, render: (value: unknown) => <Tag color={value === true ? 'green' : 'default'}>
      {value === true ? t('ai.common.enabled', '已启用') : value === false ? t('ai.common.disabled', '已禁用') : '—'}</Tag>},
    autoSyncEnabled: {width: 110, render: (value: unknown) => value === true
      ? <Tag color="blue">{t('ai.providers.page.autoSync.on', '自动同步')}</Tag>
      : <Typography.Text type="secondary">{value === false ? t('ai.providers.page.autoSync.off', '手动同步') : '—'}</Typography.Text>},
    lastStatus: {width: 110, render: (value: string) => {
      const descriptor = resolveProviderStatus(value);
      return <Tag color={descriptor.tone === 'success' ? 'green' : descriptor.tone === 'error' ? 'red' : 'default'}>{t(descriptor.key, descriptor.fallback)}</Tag>;
    }},
    lastMessage: {width: 180, render: (value: string) => {const descriptor = resolveProviderMessage(value); return descriptor ? t(descriptor.key, descriptor.fallback) : '—';}},
  }), [t]);
  const discoveryColumns = useMemo(() => [
    {title: t('ai.models.title.modelId', '模型 ID'), dataIndex: 'modelId', width: 280},
    {title: t('ai.models.title.modelType', '模型类型'), dataIndex: 'modelType', width: 140, render: (value: string) => modelTypeLabel(t, value)},
    {title: t('ai.models.title.ownedBy', '所有者'), dataIndex: 'ownedBy', width: 160},
    {title: t('ai.models.title.billingCurrency', '币种'), dataIndex: ['pricing', 'currency'], width: 80},
    {title: t('ai.models.title.inputTokenPrice', '输入 / 百万 Token'), dataIndex: ['pricing', 'inputTokenPrice'], width: 150},
    {title: t('ai.models.title.outputTokenPrice', '输出 / 百万 Token'), dataIndex: ['pricing', 'outputTokenPrice'], width: 150},
  ], [t]);
  const loading = owner?.action === 'discover' ? reads.loading.includes(discoveryChannel) : operation.busy;
  const error = actionError || (reads.errors[discoveryChannel] ? errorText(reads.errors[discoveryChannel]) : '');
  const tested = owner?.action === 'test' ? result as TestResult | undefined : undefined;
  const synced = owner?.action === 'sync' ? result as SyncResult | undefined : undefined;
  const title = owner?.action === 'test' ? t('ai.providers.button.test', '测试连接')
    : owner?.action === 'sync' ? t('ai.providers.button.sync', '同步模型') : t('ai.providers.page.modal.discover.title', '接入可用模型');

  return <div style={{height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column'}}>
    <SimpleTable {...baseConfig} schemaStaleTime={30_000} confirmDiscard allowTabEviction={!blocked}
      onInteractionLockChange={rootInteraction} onSubmit={submit} formSchemaTransform={formSchemaTransform}
      rowSelection={{isRowSelectable: () => !ownerRef.current && !operation.isBusy() && !rootLockRef.current}}
      isButtonDisabled={isButtonDisabled} customButtonEvents={customButtonEvents} columnOverrides={columnOverrides}
      errorMessageResolver={errorText} submitRefreshTargets={{page: true, schema: false}} deleteRefreshTargets={{page: true, schema: false}}/>
    {owner && operation.selected && reads.active && <Modal rootClassName="ai-provider-operation" open destroyOnHidden
      width={owner.action === 'discover' ? 1280 : 640} title={title} maskClosable={false}
      closable={!operation.busy} keyboard={!operation.busy} onCancel={close}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'close', sort: 0, text: t('ai.providers.page.operation.close', '关闭'), disabled: operation.busy, onClick: close},
        {key: 'run', sort: 1, type: 'primary', loading, disabled: !granted(owner.action),
          text: !attempted && owner.action === 'sync' ? t('ai.providers.page.operation.confirmSync', '确认同步')
            : attempted && result ? t('ai.providers.page.operation.again', '重新执行') : t('table.retry', '重试'), onClick: () => run(owner)},
      ]}/>}>
      <Typography.Paragraph>{owner.target.name || owner.target.id}</Typography.Paragraph>
      <Alert type="info" showIcon title={owner.action === 'discover'
        ? t('ai.providers.page.operation.discoveryReadOnly', '发现模型只读取远端目录，不修改本地模型。')
        : owner.action === 'test' ? t('ai.providers.page.operation.testWritesStatus', '连接测试会更新接入状态，不同步本地模型。')
          : t('ai.providers.page.operation.syncChanges', '同步会新增或更新本地模型，并将远端已移除的发现模型标为不可用；手工设置的类型和价格会保留。')}/>
      {loading ? <Alert className="ai-provider-operation-loading" type="info" showIcon style={{marginTop: 12}}
        title={owner.action === 'test' ? t('ai.providers.page.progress.testing', '正在测试连接...')
          : owner.action === 'sync' ? t('ai.providers.page.progress.syncing', '正在同步模型列表...') : t('ai.providers.page.progress.discovering', '正在获取可用模型...')}/>
        : error ? <Alert className="ai-provider-operation-error" type="error" showIcon style={{marginTop: 12}} title={error}/>
          : tested ? <Alert className="ai-provider-operation-result" type={tested.success ? 'success' : 'error'} showIcon style={{marginTop: 12}}
            title={tested.success ? t('ai.providers.page.success.test', '连接测试成功') : t('ai.providers.page.error.test', '连接测试失败')}
            description={t('ai.providers.page.modal.test.content', `已获取 ${tested.discoveredModelCount} 个可用模型`, {count: tested.discoveredModelCount})}/>
            : synced ? <><Alert className="ai-provider-operation-result" type="success" showIcon style={{marginTop: 12}}
              title={t('ai.providers.message.modelSyncSucceeded', '模型同步成功')}/>
              <Descriptions column={2} size="small" style={{marginTop: 16}}>
                <Descriptions.Item label={t('ai.providers.page.operation.discovered', '远端发现')}>{synced.discovered}</Descriptions.Item>
                <Descriptions.Item label={t('ai.providers.page.operation.created', '新增')}>{synced.created}</Descriptions.Item>
                <Descriptions.Item label={t('ai.providers.page.operation.updated', '更新')}>{synced.updated}</Descriptions.Item>
                <Descriptions.Item label={t('ai.providers.page.operation.unavailable', '标为不可用')}>{synced.unavailable}</Descriptions.Item>
              </Descriptions></>
              : owner.action === 'discover' && Array.isArray(result) ? <DataTable<DiscoveredModel> storageKey="ai.providers.discovered-models"
                query={{mode: 'local', scope: 'result', contextKey: owner.target.id, fields: {
                  modelId: {type: 'string'}, modelType: {type: 'string'}, ownedBy: {type: 'string'},
                  currency: {type: 'string', path: ['pricing', 'currency'], columnKey: 'pricing.currency'},
                  inputTokenPrice: {type: 'number', path: ['pricing', 'inputTokenPrice'], columnKey: 'pricing.inputTokenPrice'},
                  outputTokenPrice: {type: 'number', path: ['pricing', 'outputTokenPrice'], columnKey: 'pricing.outputTokenPrice'},
                }}} style={{marginTop: 16}} size="small" rowKey="modelId" dataSource={result} columns={discoveryColumns}
                pagination={{pageSize: 10, hideOnSinglePage: true}} scroll={{y: 420, x: 960}}/>
                : attempted && <Alert className="ai-provider-operation-interrupted" type="warning" showIcon style={{marginTop: 12}}
                  title={t('ai.providers.page.operation.interrupted', '模型发现已中断，请重试。')}/>}
    </Modal>}
  </div>;
}
export default function ProvidersPage() {return <ProvidersSession key={JSON.stringify(useQueryScope())}/>;}
