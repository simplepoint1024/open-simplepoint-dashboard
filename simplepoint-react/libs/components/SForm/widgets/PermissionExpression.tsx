import {useEffect, useMemo, useRef, useState} from 'react';
import {Alert, Button, Input, Select, Space, Tabs, Typography} from 'antd';
import type {WidgetProps} from '@rjsf/utils';
import {get, post, useData} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import ConditionEditor from '../../Table/ConditionEditor';
import type {QueryContract, QueryNode} from '../../Table/query';

export interface PermissionField {type: string; title: string; operators: string[]; values: string[]}
export interface PermissionPage {models: Array<{properties: Record<string, PermissionField>}>}
export const expressionBase = '/common/data-scopes/expression';
const operators = new Set(['equals', 'not:equals', 'in', 'not:in', 'is:null', 'is:not:null', 'like', 'not:like',
  'than:greater', 'than:equal:greater', 'than:less', 'than:equal:less']);
const contexts = ['user.id', 'user.organizationId', 'user.organizationIds', 'tenant.id'];
export function commonFields(page?: PermissionPage): Record<string, PermissionField> {
  const models = page?.models ?? [];
  return Object.fromEntries(Object.entries(models[0]?.properties ?? {}).filter(([name, field]) =>
    field.operators.some(op => operators.has(op)) && models.every(model => model.properties[name]?.type === field.type
      && JSON.stringify(model.properties[name]?.operators) === JSON.stringify(field.operators))));
}
// The platform visual editor omits empty leaf children; the server uses explicit immutable lists.
const visual = (node: QueryNode): QueryNode => node.children?.length
  ? {...node, children: node.children.map(visual)} : {op: node.op, field: node.field, value: node.value};
const server = (node: QueryNode): unknown => node.children
  ? {op: node.op, children: node.children.map(server)} : {...node, children: []};

export default function PermissionExpression({value, onChange, registry, disabled, readonly}: WidgetProps) {
  const {t} = useI18n();
  const resourceCode = registry.formContext?.formData?.resourceCode as string | undefined;
  const [tab, setTab] = useState('text');
  const [draft, setDraft] = useState<QueryNode>({op: 'and', children: []});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const version = useRef(0);
  const {data, error: loadError} = useData<Record<string, PermissionPage>>(['permission-expression-catalog'],
    () => get(`${expressionBase}/catalog`), {staleTime: 30_000});
  useEffect(() => {version.current++; setBusy(false); setTab('text'); setError('');}, [resourceCode]);
  const fields = useMemo(() => commonFields(data?.[resourceCode ?? '']), [data, resourceCode]);
  const contract = useMemo(() => ({version: 1, maxDepth: 3, maxNodes: 32, maxSort: 0,
    fields: Object.fromEntries(Object.entries(fields).map(([name, field]) => [name,
      {operators: field.operators.filter(op => operators.has(op)), sortable: false, javaType: field.type.substring(field.type.lastIndexOf('.') + 1)}])),
  }) as QueryContract, [fields]);
  const properties = useMemo(() => Object.fromEntries(Object.entries(fields).map(([name, field]) => [name, {
    title: field.title.startsWith('i18n:') ? t(field.title.slice(5), name) : field.title,
    type: /Boolean|^boolean$/.test(field.type) ? 'boolean' : /Integer|Long|Short|Byte|Float|Double|BigDecimal|BigInteger|^(int|long|short|byte|float|double)$/.test(field.type) ? 'number' : 'string',
    format: /LocalDate$/.test(field.type) ? 'date' : /Instant|DateTime$/.test(field.type) ? 'date-time' : undefined,
    enum: field.values.length ? field.values : undefined,
  }])), [fields, t]);
  const validate = async (ast?: QueryNode, nextTab?: string) => {
    const epoch = ++version.current;
    setBusy(true); setError('');
    try {
      const result = await post<{expression: string; ast: QueryNode}>(`${expressionBase}/validate`,
        {resourceCode, ...(ast ? {ast: server(ast)} : {expression: value})});
      if (epoch !== version.current) return;
      onChange(result.expression); setDraft(visual(result.ast)); if (nextTab) setTab(nextTab);
    } catch (reason) {if (epoch === version.current) setError((reason as Error).message || t('data-scopes.expression.invalid', '表达式校验失败'));}
    finally {if (epoch === version.current) setBusy(false);}
  };
  const locked = disabled || readonly || busy;
  return <div style={{display: 'grid', gap: 12}}>
    <Typography.Text type="secondary">{t('data-scopes.expression.hint', '仅可使用目标页面已注册的字段。文本和可视化共用规则；编辑可视化后请先应用，再保存。')}</Typography.Text>
    {(error || loadError) && <Alert type="error" title={error || t('data-scopes.expression.loadFailed', '字段元数据加载失败')} />}
    {!resourceCode ? <Alert type="info" title={t('data-scopes.expression.choosePage', '请先选择目标页面')} /> : <>
      <Tabs activeKey={tab} onChange={next => {
        if (next === 'visual' && value) void validate(undefined, next);
        else if (next === 'visual') {setDraft({op: 'and', children: []}); setTab(next);}
        else setTab(next);
      }} items={[
        {key: 'text', label: t('data-scopes.expression.text', '表达式'), children: <Input.TextArea value={value ?? ''}
          disabled={locked} autoSize={{minRows: 4, maxRows: 12}} maxLength={8192}
          placeholder={'createdBy = $user.id AND enabled = true'}
          onChange={event => {version.current++; onChange(event.target.value); setError('');}} />},
        {key: 'visual', label: t('data-scopes.expression.visual', '可视化'), children: <fieldset disabled={locked}
          style={{border: 0, margin: 0, padding: 0, minWidth: 0, pointerEvents: locked ? 'none' : undefined}}>
          <ConditionEditor node={draft} contract={contract} properties={properties}
            onChange={node => {version.current++; setDraft(node); onChange(''); setError('');}}
            renderValue={(node, change, input) => {
              if (node.op.startsWith('is:')) return null;
              const context = (node.value as unknown as {context?: string})?.context;
              const available = fields[node.field ?? '']?.type === 'java.lang.String'
                ? contexts.filter(key => key === 'user.organizationIds' ? node.op.includes('in') : !node.op.includes('in')) : [];
              return <Space orientation="vertical" style={{width: '100%'}}>
                <Select style={{width: '100%'}} value={context ?? 'literal'} options={[
                  {value: 'literal', label: t('data-scopes.expression.literal', '固定值')},
                  ...available.map(key => ({value: key, label: `$${key}`}))]}
                  onChange={key => change({...node, value: key === 'literal' ? undefined : {context: key} as unknown as QueryNode['value']})} />
                {!context && input}
              </Space>;
            }} />
        </fieldset>},
      ]} />
      <Button disabled={locked || !Object.keys(fields).length} loading={busy}
        onClick={() => void validate(tab === 'visual' ? draft : undefined)}>
        {tab === 'visual' ? t('data-scopes.expression.apply', '应用可视化配置') : t('data-scopes.expression.validate', '校验并格式化')}
      </Button>
    </>}
  </div>;
}
