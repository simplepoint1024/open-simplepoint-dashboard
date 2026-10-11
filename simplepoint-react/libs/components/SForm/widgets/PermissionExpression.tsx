import {useEffect, useMemo, useRef, useState} from 'react';
import {Alert, Input, Select, Space, Tabs, Typography} from 'antd';
import type {WidgetProps} from '@rjsf/utils';
import {post} from '@simplepoint/shared/api/methods';
import {usePermissionExpressionCatalog} from '@simplepoint/shared/hooks/usePermissionExpressionCatalog';
import type {PermissionField, PermissionPage} from '@simplepoint/shared/api/permissionCatalog';
import {useOwnedReads} from '@simplepoint/shared/hooks/useOwnedReads';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {expressionLabels, expressionWords, permissionLabels} from '@simplepoint/shared/i18n/permissionLabels';
import ConditionEditor from '../../Table/ConditionEditor';
import TableActions from '../../Table/Actions';
import type {QueryContract, QueryNode} from '../../Table/query';

export type {PermissionField, PermissionPage} from '@simplepoint/shared/api/permissionCatalog';
export const expressionBase = '/common/data-scopes/expression';
const operators = new Set(['equals', 'not:equals', 'in', 'not:in', 'is:null', 'is:not:null', 'like', 'not:like',
  'than:greater', 'than:equal:greater', 'than:less', 'than:equal:less']);
const contexts = ['user.id', 'user.organizationId', 'user.organizationIds', 'tenant.id'];
export function commonFields(page?: PermissionPage): Record<string, PermissionField> {
  const models = page?.models ?? [];
  const boxed = (type: string) => ({boolean: 'java.lang.Boolean', byte: 'java.lang.Byte', short: 'java.lang.Short',
    int: 'java.lang.Integer', long: 'java.lang.Long', float: 'java.lang.Float', double: 'java.lang.Double'}[type] ?? type);
  return Object.fromEntries(Object.entries(models[0]?.properties ?? {}).flatMap(([name, field]) => {
    if (!models.every(model => {
      const other = model.properties[name];
      return other && boxed(other.type) === boxed(field.type)
        && JSON.stringify([...other.values].sort()) === JSON.stringify([...field.values].sort());
    })) return [];
    const common = field.operators.filter(op => operators.has(op) && models.every(model => model.properties[name].operators.includes(op)));
    return common.length ? [[name, {...field, type: boxed(field.type), operators: common}]] : [];
  }));
}
// The platform visual editor omits empty leaf children; the server uses explicit immutable lists.
const visual = (node: QueryNode): QueryNode => node.children?.length
  ? {...node, children: node.children.map(visual)} : {op: node.op, field: node.field, value: node.value};
const server = (node: QueryNode): unknown => node.children
  ? {op: node.op, children: node.children.map(server)} : {...node, children: []};

export default function PermissionExpression({value, onChange, registry, disabled, readonly}: WidgetProps) {
  const {t, ensure, locale} = useI18n();
  // This field uses contract titles and static context labels, not the caller's whole presentation directory.
  const labels = useMemo(() => permissionLabels(t), [t]);
  const [textDraft, setTextDraft] = useState('');
  const resourceCode = registry.formContext?.formData?.resourceCode as string | undefined;
  const [tab, setTab] = useState(value ? 'text' : 'visual');
  const [draft, setDraft] = useState<QueryNode>({op: 'and', children: []});
  const [error, setError] = useState('');
  const operation = useOperationScope(resourceCode), reads = useOwnedReads(operation, !disabled && !readonly);
  const busy = reads.loading.length > 0;
  const source = useRef({resourceCode, canonical: String(value ?? ''), localized: undefined as unknown});
  const visualDirty = useRef(false);
  const {data, error: loadError, refetch} = usePermissionExpressionCatalog(resourceCode);
  useEffect(() => {
    const titles = (data?.[resourceCode ?? '']?.models ?? []).flatMap(model => Object.values(model.properties).map(field => field.title));
    void ensure([...new Set(['permissions', 'organizations', ...(resourceCode ? [resourceCode.replace(/\.view$/, '').split('.')[0]] : []),
      ...titles.filter(title => title?.startsWith('i18n:')).map(title => title.slice(5).split('.')[0])])]);
  }, [data, resourceCode, ensure, locale]);
  const fields = useMemo(() => commonFields(data?.[resourceCode ?? '']), [data, resourceCode]);
  const localized = useMemo(() => expressionLabels(
    Object.fromEntries(Object.entries(fields).map(([name, field]) => [name, labels.field(name, field, resourceCode)])),
    Object.fromEntries(contexts.map(key => [key, labels.context(key)])), expressionWords(t)), [fields, labels, resourceCode, t]);
  useEffect(() => {
    const canonical = String(value ?? ''), previous = source.current;
    if (previous.resourceCode !== resourceCode) {visualDirty.current = false; setDraft({op: 'and', children: []}); setTab(canonical ? 'text' : 'visual'); setError('');}
    // Preserve a partially typed translated draft when its canonical value is still invalid/empty.
    if (previous.resourceCode !== resourceCode || previous.canonical !== canonical || previous.localized !== localized && canonical
      || previous.localized === undefined) setTextDraft(localized.encode(canonical));
    source.current = {resourceCode, canonical, localized};
  }, [value, localized, resourceCode]);
  const editText = (text: string) => {
    if (disabled || readonly || reads.isBusy() || !operation.current() || !operation.isSelected() || !reads.active) return;
    setTextDraft(text);
    try {const canonical = localized.decode(text); source.current.canonical = canonical; onChange(canonical); setError('');}
    catch {source.current.canonical = ''; onChange(''); setError(t('permissions.expression.invalid', '请使用已翻译的字段和变量名称，并检查表达式是否完整'));}
  };
  const contract = useMemo(() => ({version: 1, maxDepth: 3, maxNodes: 32, maxSort: 0,
    fields: Object.fromEntries(Object.entries(fields).map(([name, field]) => [name,
      {operators: field.operators.filter(op => operators.has(op)), sortable: false, javaType: field.type.substring(field.type.lastIndexOf('.') + 1)}])),
  }) as QueryContract, [fields]);
  const properties = useMemo(() => Object.fromEntries(Object.entries(fields).map(([name, field]) => [name, {
    title: localized.fields[name],
    type: /Boolean|^boolean$/.test(field.type) ? 'boolean' : /Integer|Long|Short|Byte|Float|Double|BigDecimal|BigInteger|^(int|long|short|byte|float|double)$/.test(field.type) ? 'number' : 'string',
    format: /LocalDate$/.test(field.type) ? 'date' : /Instant|DateTime$/.test(field.type) ? 'date-time' : undefined,
    oneOf: field.values.length ? field.values.map((value, index) => ({const: value, title: labels.option(value, field.title.replace(/^i18n:/, ''), index)})) : undefined,
  }])), [fields, localized, labels]);
  const validate = async (ast?: QueryNode, nextTab?: string) => {
    if (disabled || readonly || !resourceCode || !operation.current() || !operation.isSelected() || !reads.active || reads.isBusy()) return;
    if (!ast && textDraft && !value) {setError(t('permissions.expression.invalid', '请使用已翻译的字段和变量名称，并检查表达式是否完整')); return;}
    const release = registry.formContext?.beginPreparation?.();
    if (registry.formContext?.beginPreparation && !release) return;
    setError(''); reads.clearErrors();
    try {
      await reads.run('expression-validation', signal => post<{expression: string; ast: QueryNode}>(`${expressionBase}/validate`,
        {resourceCode, ...(ast ? {ast: server(ast)} : {expression: value})}, {signal, notifyOnError: false}), result => {
        if (typeof result?.expression !== 'string' || !result.expression || !result.ast || typeof result.ast.op !== 'string') {
          setError(t('data-scopes.expression.invalid', '表达式校验失败')); return;
        }
        source.current.canonical = result.expression;
        visualDirty.current = false;
        onChange(result.expression); setTextDraft(localized.encode(result.expression)); setDraft(visual(result.ast)); if (nextTab) setTab(nextTab);
      });
    } finally {release?.();}
  };
  const locked = disabled || readonly || busy || !operation.selected || !reads.active;
  return <div className="permission-expression-widget" style={{display: 'grid', gap: 12}}>
    <Typography.Text type="secondary">{t('data-scopes.expression.hint', '仅可使用目标页面已注册的字段。文本和可视化共用规则；编辑可视化后请先应用，再保存。')}</Typography.Text>
    {(error || loadError || Object.keys(reads.errors).length > 0) && <Alert type="error"
      title={error || (loadError ? t('data-scopes.expression.loadFailed', '字段元数据加载失败') : t('data-scopes.expression.invalid', '表达式校验失败'))}
      action={loadError ? <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, text: t('table.retry', '重试'), onClick: () => {
        if (operation.current() && operation.isSelected() && reads.active && !reads.isBusy()) void refetch({cancelRefetch: false});
      }}]} /> : undefined} />}
    {!resourceCode ? <Alert type="info" title={t('data-scopes.expression.choosePage', '请先选择目标页面')} /> : <>
      <Tabs activeKey={tab} onChange={next => {
        if (locked || reads.isBusy() || !reads.active || !operation.current() || !operation.isSelected()) return;
        if (next === 'visual' && textDraft && !value && tab === 'text') {setError(t('permissions.expression.invalid', '请使用已翻译的字段和变量名称，并检查表达式是否完整')); return;}
        if (next === 'text' && !value && visualDirty.current) {setError(t('data-scopes.expression.unapplied', '请先应用可视化配置，或继续编辑当前规则')); return;}
        if (next === 'visual' && value) void validate(undefined, next);
        else if (next === 'visual') {setDraft({op: 'and', children: []}); setTab(next);}
        else setTab(next);
      }} items={[
        {key: 'text', disabled: locked, label: t('data-scopes.expression.text', '表达式'), children: <Space orientation="vertical" style={{width: '100%'}}>
          <Select value={undefined} placeholder={t('permissions.insertField', '插入字段')} showSearch optionFilterProp="label" style={{width: '100%'}}
            options={Object.entries(localized.fields).map(([key, label]) => ({value: key, label}))}
            onChange={key => {if (key) editText(textDraft + `【${localized.fields[key]}】`);}} disabled={locked} />
          <Input.TextArea value={textDraft}
          disabled={locked} autoSize={{minRows: 4, maxRows: 12}} maxLength={8192}
          placeholder={t('permissions.expression.placeholder', '请通过可视化生成表达式，或插入字段后编辑条件')}
          onChange={event => editText(event.target.value)} /></Space>},
        {key: 'visual', disabled: locked, label: t('data-scopes.expression.visual', '可视化'), children: <fieldset disabled={locked}
          style={{border: 0, margin: 0, padding: 0, minWidth: 0, pointerEvents: locked ? 'none' : undefined}}>
          <ConditionEditor node={draft} contract={contract} properties={properties}
            onChange={node => {if (locked || reads.isBusy()) return; visualDirty.current = true; setDraft(node); source.current.canonical = ''; onChange(''); setTextDraft(''); setError('');}}
            renderValue={(node, change, input) => {
              if (node.op.startsWith('is:')) return null;
              const context = (node.value as unknown as {context?: string})?.context;
              const available = fields[node.field ?? '']?.type === 'java.lang.String'
                ? contexts.filter(key => key === 'user.organizationIds' ? node.op.includes('in') : !node.op.includes('in')) : [];
              return <Space orientation="vertical" style={{width: '100%'}}>
                <Select style={{width: '100%'}} value={context ?? 'literal'} options={[
                  {value: 'literal', label: t('data-scopes.expression.literal', '固定值')},
                  ...available.map(key => ({value: key, label: labels.context(key)}))]}
                  onChange={key => change({...node, value: key === 'literal' ? undefined : {context: key} as unknown as QueryNode['value']})} />
                {!context && input}
              </Space>;
            }} />
        </fieldset>},
      ]} />
      <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'validate', sort: 0,
        disabled: locked || !Object.keys(fields).length, loading: busy, onClick: () => {void validate(tab === 'visual' ? draft : undefined);},
        text: tab === 'visual' ? t('data-scopes.expression.apply', '应用可视化配置') : t('data-scopes.expression.validate', '校验并格式化')}]} />
    </>}
  </div>;
}
