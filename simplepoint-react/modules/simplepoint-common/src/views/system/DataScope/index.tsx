import SimpleTable from '@simplepoint/components/SimpleTable';
import TableActions from '@simplepoint/components/Table/Actions';
import api from '@/api/index';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {Alert} from 'antd';
import {get, post} from '@simplepoint/shared/api/methods';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageActive} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePermissionLabels} from '@simplepoint/shared/hooks/usePermissionLabels';
import {expressionLabels, expressionWords, contextNames} from '@simplepoint/shared/i18n/permissionLabels';
import EffectiveScopePreview from './EffectiveScopePreview';
import {prepareScopeData, scopeFormSchema, scopeFormUiSchema, scopeTableSchema} from './model';

const baseConfig = api['rbac-data-scopes'];
const expressionBase = `${baseConfig.baseUrl}/expression`;

export default function DataScopePage() {
  return <DataScopeSession key={JSON.stringify(useQueryScope())} />;
}
function DataScopeSession() {
  const operation = useOperationScope(), active = usePageActive();
  const {ensure, locale, t} = useI18n();
  const preview = useRef(false), interaction = useRef(false);
  const [previewOpen, setPreviewOpen] = useState(false), [locked, setLocked] = useState(false);
  const onPreview = useCallback((open: boolean) => {preview.current = open; setPreviewOpen(open);}, []);
  const onLock = useCallback((busy: boolean) => {interaction.current = busy; setLocked(busy);}, []);
  const {labels, data: presentation} = usePermissionLabels();
  useEffect(() => {void ensure([...baseConfig.i18nNamespaces, 'permissions']);}, [ensure, locale]);
  const {data: catalog, error, refetch} = useOwnedData<Record<string, unknown>>(['permission-expression-catalog'],
    ({signal}) => get(`${expressionBase}/catalog`, undefined, {signal, notifyOnError: false}), {staleTime: 30_000, retry: false});
  const schemaTransform = useCallback((schema: any) => scopeTableSchema(schema, labels.scope), [labels]);
  const displayExpression = (value: string, row: {resourceCode?: string}) => {
    if (!value) return '—';
    const fields = Object.assign({}, ...(presentation?.pages[row.resourceCode ?? '']?.models ?? []).map(model => model.properties));
    return expressionLabels(Object.fromEntries(Object.entries(fields).map(([name, field]) => [name, labels.field(name, field as any, row.resourceCode)])),
      Object.fromEntries(contextNames.map(name => [name, labels.context(name)])), expressionWords(t)).encode(value);
  };
  const pages = useMemo(() => Object.keys(catalog ?? {}).map(code => ({const: code, title: labels.page(code)})), [catalog, labels]);
  const formSchemaTransform = useCallback((schema: any, editing: any) => scopeFormSchema(schema, editing, pages), [pages]);
  const beforeSubmit = useCallback(async ({formData, signal}: any) => {
    if (formData?.type === 'EXPRESSION') {
      const result = await post<{expression: string}>(`${expressionBase}/validate`,
        {resourceCode: formData.resourceCode, expression: formData.expression}, {signal, notifyOnError: false});
      return {...formData, expression: result.expression, customDeptIds: []};
    }
    return prepareScopeData(formData);
  }, []);
  return <div className="data-scope-page" style={{height: '100%', width: '100%', minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column'}}>
    <div style={{flexShrink: 0}}><EffectiveScopePreview isBlocked={() => interaction.current || locked} onOpenChange={onPreview} /></div>
    {error && <Alert type="error" title={t('data-scopes.expression.loadFailed', '字段元数据加载失败')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, text: t('table.retry', '重试'), onClick: () => {
        if (operation.current() && operation.isSelected() && active) void refetch();
      }}]} />} />}
    <div style={{flex: 1, minHeight: 0, minWidth: 0}}>
      <SimpleTable {...baseConfig} drawerWidth="min(960px, 100vw)" confirmDiscard
        schemaStaleTime={30_000} allowTabEviction={!previewOpen}
        onInteractionLockChange={onLock} isButtonDisabled={() => preview.current}
        rowSelection={{isRowSelectable: () => !preview.current}}
        initialValues={{type: 'SELF', customDeptIds: []}}
        submitRefreshTargets={{page: true, schema: false}} deleteRefreshTargets={{page: true, schema: false}}
        schemaTransform={schemaTransform}
        columnOverrides={{type: {render: value => labels.scope(value)}, expression: {render: displayExpression}}}
        formSchemaTransform={formSchemaTransform} beforeSubmit={beforeSubmit}
        formDataTransform={(next, previous) => next.resourceCode !== previous.resourceCode ? {...next, expression: ''} : next}
        formUiSchema={scopeFormUiSchema} />
    </div>
  </div>;
}
