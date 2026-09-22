import SimpleTable from '@simplepoint/components/SimpleTable';
import api from '@/api/index';
import {useCallback, useEffect} from 'react';
import {Alert} from 'antd';
import {get, post, useData} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import EffectiveScopePreview from './EffectiveScopePreview';

const baseConfig = api['rbac-data-scopes'];
const expressionBase = `${baseConfig.baseUrl}/expression`;

export default function DataScopePage() {
  const {ensure, locale, t} = useI18n();
  useEffect(() => {void ensure(baseConfig.i18nNamespaces);}, [ensure, locale]);
  const {data: catalog, error} = useData<Record<string, unknown>>(['permission-expression-catalog'],
    () => get(`${expressionBase}/catalog`), {staleTime: 30_000});
  const formSchemaTransform = useCallback((schema: any) => {
    const next = structuredClone(schema);
    const props = next.properties;
    if (!props?.type) return next;
    const organization = {...props.customDeptIds, type: 'array', items: {type: 'string'}, uniqueItems: true, minItems: 1};
    const pages = Object.keys(catalog ?? {});
    const resource = {...props.resourceCode, type: 'string', ...(pages.length ? {enum: pages} : {readOnly: true})};
    const expression = {...props.expression, type: 'string', minLength: 1, maxLength: 8192};
    delete props.customDeptIds; delete props.resourceCode; delete props.expression;
    next.required = [...new Set([...(next.required ?? []), 'name', 'type'])];
    const types = props.type.enum ?? ['ALL', 'CUSTOM', 'DEPT', 'DEPT_AND_BELOW', 'SELF', 'EXPRESSION'];
    next.dependencies = {...next.dependencies, type: {oneOf: [
      {properties: {type: {enum: ['CUSTOM']}, customDeptIds: organization}, required: ['customDeptIds']},
      {properties: {type: {enum: ['EXPRESSION']}, resourceCode: resource, expression}, required: ['resourceCode', 'expression']},
      {properties: {type: {enum: types.filter((value: string) => !['CUSTOM', 'EXPRESSION'].includes(value))}}},
    ]}};
    return next;
  }, [catalog]);
  const beforeSubmit = useCallback(async ({formData}: any) => {
    if (formData?.type === 'EXPRESSION') {
      const result = await post<{expression: string}>(`${expressionBase}/validate`,
        {resourceCode: formData.resourceCode, expression: formData.expression});
      return {...formData, expression: result.expression, customDeptIds: []};
    }
    return {...formData, customDeptIds: formData?.type === 'CUSTOM' ? formData.customDeptIds : [], resourceCode: null, expression: null};
  }, []);
  return <div style={{height: '100%', width: '100%', minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column'}}>
    <div style={{flexShrink: 0}}><EffectiveScopePreview /></div>
    {error && <Alert type="error" title={t('data-scopes.expression.loadFailed', '字段元数据加载失败')} />}
    <div style={{flex: 1, minHeight: 0, minWidth: 0}}>
      <SimpleTable {...baseConfig} drawerWidth="min(960px, 100vw)" confirmDiscard
        initialValues={{type: 'SELF', customDeptIds: []}}
        submitRefreshTargets={{page: true, schema: false}} deleteRefreshTargets={{page: true, schema: false}}
        formSchemaTransform={formSchemaTransform} beforeSubmit={beforeSubmit}
        formUiSchema={(draft) => ({'ui:order': ['name', 'type', ...(draft.type === 'CUSTOM' ? ['customDeptIds'] : draft.type === 'EXPRESSION' ? ['resourceCode', 'expression'] : []), '*'],
          customDeptIds: {'ui:widget': 'OrgTreeMultiSelect'}, expression: {'ui:widget': 'PermissionExpression'},
          description: {'ui:widget': 'textarea'}})} />
    </div>
  </div>;
}
