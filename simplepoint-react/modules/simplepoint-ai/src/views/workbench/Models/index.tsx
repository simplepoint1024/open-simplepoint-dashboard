import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {SimpleTableSubmitAction} from '@simplepoint/components/SimpleTable/types';
import type {TableButtonProps} from '@simplepoint/components/Table';
import type {EntitySelectSource} from '@simplepoint/components/EntitySelect';
import {entitySearchParams} from '@simplepoint/components/EntitySelect/query';
import {get, post, put} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageActive, usePageRetention} from '@simplepoint/shared/page/hooks';
import {App, Tag} from 'antd';
import {useCallback, useMemo, useRef, useState} from 'react';
import {modelTypeLabel, resourceScopeLabel} from '../modelLabels';
import {localizeWorkbenchError, resolveWorkbenchOperationError} from '../workbenchErrorCodes';
import ModelDebugDialog, {type DebugModel} from './ModelDebugDialog';
import {debugTarget, modelEditorSchema, modelIdentity, modelInput, providerLookupBatches, providerOption, type ModelRow} from './model';

const baseConfig = api['ai-workbench.models'], providerBaseConfig = api['ai-workbench.providers'];
const modelTypes = ['LLM', 'EMBEDDING', 'RERANK', 'IMAGE', 'AUDIO', 'MODERATION', 'MULTIMODAL', 'OTHER'];
const modelOperationFallback = {key: 'ai.models.error.operationFailed', fallback: '模型操作失败，请稍后重试'};

function ModelsSession() {
  const {t, locale} = useI18n(), {message} = App.useApp();
  const active = usePageActive(), operation = useOperationScope(baseConfig.baseUrl);
  const permissions = useSchema(baseConfig.baseUrl, {staleTime: 30_000, retry: false});
  const granted = useCallback((key: string) => !permissions.error && !!permissions.data?.buttons.some(button => button.key === key),
    [permissions.error, permissions.data]);
  const [debugModel, setDebugModel] = useState<DebugModel>(), debugRef = useRef<DebugModel | undefined>(undefined);
  const [rootLocked, setRootLocked] = useState(false), rootLockRef = useRef(false);
  const blocked = !!debugModel || operation.busy || rootLocked;
  usePageRetention(!blocked, blocked);
  const rootInteraction = useCallback((locked: boolean) => {rootLockRef.current = locked; setRootLocked(locked);}, []);
  const providerSource = useMemo<EntitySelectSource>(() => ({
    key: `ai-provider-options-v1:${locale}`, searchParams: entitySearchParams,
    option: row => providerOption(row, t('ai.common.disabled', '已禁用')),
    resolve: async (ids, signal) => {
      const options = [];
      for (const batch of providerLookupBatches(ids)) {
        const rows = await get<unknown>(`${providerBaseConfig.baseUrl}/selected`, {ids: batch.join(',')}, {signal, notifyOnError: false});
        if (!Array.isArray(rows)) throw new Error(t('ai.models.page.providerLookupInvalid', '接入回读结果无效，请重试。'));
        const requested = new Set(batch);
        for (const row of rows) {
          if (!row || typeof row !== 'object' || !requested.has(row.id)) continue;
          const option = providerOption(row, t('ai.common.disabled', '已禁用')); if (option) options.push(option);
        }
      }
      return options;
    },
  }), [t, locale]);
  const formSchemaTransform = useCallback((schema: any, record: ModelRow | null) => modelEditorSchema(schema, record,
    modelTypes.map(value => ({const: value, title: modelTypeLabel(t, value)}))), [t]);
  const formUiSchema = useCallback((draft: ModelRow) => ({
    providerId: typeof draft.providerId === 'string' && /^\*+$/.test(draft.providerId.trim())
      ? {'ui:widget': 'text'}
      : {'ui:widget': 'EntitySelect', 'ui:options': {endpoint: providerBaseConfig.baseUrl, source: providerSource}},
  }), [providerSource]);
  const submit = useCallback(async (action: SimpleTableSubmitAction, values: Record<string, unknown>, current: ModelRow | null) => {
    const id = modelIdentity(current);
    const unavailable = () => new Error(t('table.actionUnavailable', '此操作暂不可用，请刷新页面后重试'));
    if (debugRef.current || !granted(action) || action === 'edit' && !id) throw unavailable();
    const request = operation.begin(); if (!request) throw unavailable();
    try {
      const data = modelInput(permissions.data?.schema, values);
      const updated = action === 'edit'
        ? await put<ModelRow>(baseConfig.baseUrl, {...data, id}, {signal: request.signal, notifyOnError: false})
        : await post<ModelRow>(baseConfig.baseUrl, data, {signal: request.signal, notifyOnError: false});
      if (!operation.current(request.signal)) return undefined;
      if (operation.isSelected()) message.success(action === 'edit' ? t('table.editSuccess', '修改成功') : t('table.addSuccess', '新增成功'));
      return updated;
    } catch (error) {if (operation.current(request.signal)) throw error; return undefined;}
    finally {operation.finish(request);}
  }, [granted, operation.begin, operation.current, operation.isSelected, operation.finish, permissions.data?.schema, message, t]);
  const columnOverrides = useMemo(() => {
    const enabledTag = (value: unknown) => <Tag color={value === true ? 'green' : 'default'}>
      {value === true ? t('ai.common.enabled', '已启用') : value === false ? t('ai.common.disabled', '已禁用') : '—'}</Tag>;
    return {
      scopeType: {width: 110, render: (value: string) => <Tag color={value === 'TENANT' ? 'blue' : value === 'SYSTEM' ? 'purple' : 'default'}>{resourceScopeLabel(t, value)}</Tag>},
      providerId: {width: 200, render: (value: string, record: ModelRow) =>
        typeof record.providerName === 'string' && record.providerName.trim() && !/^\*+$/.test(record.providerName.trim()) ? record.providerName : value || '—'},
      modelId: {width: 280, ellipsis: true},
      modelType: {width: 140, render: (value: string) => <Tag color="blue">{modelTypeLabel(t, value)}</Tag>},
      enabled: {width: 100, render: enabledTag}, billingEnabled: {width: 110, render: enabledTag},
      available: {width: 100, render: (value: unknown) => <Tag color={value === true ? 'green' : value === false ? 'red' : 'default'}>
        {value === true ? t('ai.models.page.available', '可用') : value === false ? t('ai.models.page.unavailable', '不可用') : '—'}</Tag>},
      billingCurrency: {width: 100}, inputTokenPrice: {width: 170}, outputTokenPrice: {width: 170},
    };
  }, [t]);
  const customButtonEvents = useMemo<Record<string, (keys: React.Key[], rows: ModelRow[], props: TableButtonProps) => void>>(() => ({
    debug: (_keys, rows) => {
      if (!operation.current() || !operation.isSelected() || operation.isBusy() || rootLockRef.current || debugRef.current
        || !granted('debug') || rows.length !== 1) return;
      const captured = debugTarget(rows[0]); if (!captured) return;
      debugRef.current = captured; setDebugModel(captured);
    },
  }), [operation.current, operation.isSelected, operation.isBusy, granted]);
  const isButtonDisabled = useCallback((button: TableButtonProps, _keys: React.Key[], rows: ModelRow[]) =>
    !operation.selected || !!debugRef.current || operation.isBusy() || rootLockRef.current || !granted(String(button.key))
      || button.key !== 'add' && rows.some(row => button.key === 'debug' ? !debugTarget(row) : !modelIdentity(row)),
  [operation.selected, operation.isBusy, granted, debugModel, rootLocked]);
  const errorMessageResolver = useCallback((error: unknown) => localizeWorkbenchError(t, resolveWorkbenchOperationError(error, modelOperationFallback)), [t]);

  return <div style={{height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column'}}>
    <SimpleTable {...baseConfig} schemaStaleTime={30_000} confirmDiscard allowTabEviction={!blocked} onInteractionLockChange={rootInteraction}
      rowSelection={{isRowSelectable: () => !debugRef.current && !operation.isBusy() && !rootLockRef.current}}
      formSchemaTransform={formSchemaTransform} formUiSchema={formUiSchema} onSubmit={submit} columnOverrides={columnOverrides}
      customButtonEvents={customButtonEvents} isButtonDisabled={isButtonDisabled} errorMessageResolver={errorMessageResolver}
      submitRefreshTargets={{page: true, schema: false}} deleteRefreshTargets={{page: true, schema: false}}/>
    <ModelDebugDialog key={debugModel?.id ?? 'closed'} model={debugModel} open={!!debugModel && operation.selected && active} authorized={granted('debug')}
      onClose={() => {debugRef.current = undefined; setDebugModel(undefined);}}/>
  </div>;
}
export default function ModelsPage() {return <ModelsSession key={JSON.stringify(useQueryScope())}/>;}
