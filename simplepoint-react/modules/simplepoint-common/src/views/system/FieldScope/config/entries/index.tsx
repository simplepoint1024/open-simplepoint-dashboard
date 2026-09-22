import {Alert, Button, Empty, Select, Space, Table, Tag, Typography, type SelectProps} from 'antd';
import {DeleteOutlined, PlusOutlined, ReloadOutlined} from '@simplepoint/shared/types/icon';
import {useEffect, useMemo, useState} from 'react';
import {fetchFieldPageCatalog, type FieldScopeEntryDto} from '@/api/system/field-scope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useData} from '@simplepoint/shared/api/methods';

const ACCESS = ['EDITABLE', 'VISIBLE', 'MASKED', 'HIDDEN'];
const keyOf = (entry: Pick<FieldScopeEntryDto, 'resource' | 'field'>) => `${entry.resource}#${entry.field}`;
const EMPTY: FieldScopeEntryDto[] = [];

/** Controlled editor shared by creation, editing and the registered configuration action. */
export default function FieldEntries({value = EMPTY, onChange, disabled, onPendingChange}: {
  value?: FieldScopeEntryDto[]; onChange?: (entries: FieldScopeEntryDto[]) => void; disabled?: boolean;
  onPendingChange?: (pending: boolean) => void;
}) {
  const {t, ensure, locale} = useI18n();
  const [page, setPage] = useState<string>();
  const [selected, setSelected] = useState<string[]>([]);
  const [access, setAccess] = useState('VISIBLE');
  const {data: catalog, isFetching, error, refetch} = useData(['fieldScopePageCatalog'], fetchFieldPageCatalog, {staleTime: 30_000});
  const label = (text: string, fallback: string) => text?.startsWith('i18n:') ? t(text.slice(5), fallback) : text || fallback;
  const accessOptions = ACCESS.map(value => ({value, label: t(`field-scopes.title.entry.access.${value}`, value)}));
  const metadata = useMemo(() => {
    const fields = new Map<string, {title: string; model: string; pages: string[]}>();
    Object.entries(catalog ?? {}).forEach(([code, page]) => page.models.forEach(model =>
      Object.entries(model.properties).forEach(([field, property]) => {
        const key = keyOf({resource: model.model, field});
        const item = fields.get(key) ?? {title: property.title, model: model.model, pages: []};
        item.pages.push(code); fields.set(key, item);
      })));
    return fields;
  }, [catalog]);
  useEffect(() => {
    const titles = [
      ...Object.values(catalog ?? {}).map(item => item.title),
      ...(catalog?.[page ?? '']?.models ?? []).flatMap(model => Object.values(model.properties).map(field => field.title)),
      ...value.map(entry => metadata.get(keyOf(entry))?.title ?? ''),
    ];
    const namespaces = [...new Set(titles.filter(title => title.startsWith('i18n:')).map(title => title.slice(5).split('.')[0]))];
    if (namespaces.length) void ensure(namespaces);
  }, [catalog, page, value, metadata, ensure, locale]);
  const configured = useMemo(() => new Set(value.map(keyOf)), [value]);
  const pageOptions = Object.entries(catalog ?? {}).map(([code, item]) => ({value: code, label: `${label(item.title, code)} · ${code}`}));
  const modelOptions = (catalog?.[page ?? '']?.models ?? []).map(model => ({
    label: model.model.split('.').pop(),
    options: Object.entries(model.properties).map(([field, property]) => ({
      value: keyOf({resource: model.model, field}), label: `${label(property.title, field)} · ${field}`,
      disabled: configured.has(keyOf({resource: model.model, field})),
    })),
  }));
  const fieldOptions: SelectProps['options'] = modelOptions.length === 1 ? modelOptions[0].options : modelOptions;
  const missing = !error && !!catalog && value.some(entry => !metadata.has(keyOf(entry)));
  return <div style={{display: 'grid', gap: 12, minWidth: 0}}>
    <Typography.Text type="secondary">{t('field-scopes.editor.hint', '选择页面资源后批量添加字段；未配置的字段沿用原有权限。规则保存后仍需在角色资源配置中绑定。')}</Typography.Text>
    {error && <Alert type="error" title={t('field-scopes.message.catalogFailed', '字段目录加载失败，请刷新后重试')}
      action={<Button icon={<ReloadOutlined />} onClick={() => void refetch()}>{t('table.retry', '重试')}</Button>} />}
    {missing && <Alert type="warning" title={t('field-scopes.editor.unavailable', '部分已有字段的元数据当前不可用，配置会保留；可以移除，但不能修改其访问级别。')} />}
    <div style={{display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12}}>
      <Select aria-label={t('field-scopes.editor.resource', '页面资源')} showSearch optionFilterProp="label"
        placeholder={t('field-scopes.editor.resource', '页面资源')} value={page} options={pageOptions}
        loading={isFetching} disabled={disabled || !!error} onChange={code => {setPage(code); setSelected([]); onPendingChange?.(false);}} />
      <Select aria-label={t('field-scopes.editor.fields', '选择字段')} mode="multiple" showSearch optionFilterProp="label"
        placeholder={t('field-scopes.editor.fields', '选择字段')} value={selected} options={fieldOptions}
        maxTagCount="responsive" disabled={disabled || !page || !!error}
        onChange={keys => {setSelected(keys); onPendingChange?.(keys.length > 0);}} />
      <Space.Compact style={{width: '100%'}}>
        <Select aria-label={t('field-scopes.title.entry.access', '访问级别')} value={access} options={accessOptions}
          style={{flex: 1}} disabled={disabled} onChange={setAccess} />
        <Button icon={<PlusOutlined />} disabled={disabled || !!error || !selected.length || value.length + selected.length > 500}
          onClick={() => {
            const added = selected.filter(key => !configured.has(key)).map(key => {
              const index = key.lastIndexOf('#'); return {resource: key.slice(0, index), field: key.slice(index + 1), access};
            });
            onChange?.([...value, ...added]); setSelected([]); onPendingChange?.(false);
          }}>{t('field-scopes.editor.addFields', '添加字段')}</Button>
      </Space.Compact>
    </div>
    {!!selected.length && <Typography.Text type="secondary">{t('field-scopes.editor.pending', '请点击“添加字段”，或清空已选字段后保存。')}</Typography.Text>}
    {!isFetching && catalog && !pageOptions.length && <Empty description={t('field-scopes.editor.empty', '当前没有已授权且支持字段权限的页面资源')} />}
    <Table size="small" rowKey={keyOf} dataSource={value} pagination={{pageSize: 8, showSizeChanger: false, hideOnSinglePage: true}}
      scroll={{x: 680}} columns={[
        {title: t('field-scopes.editor.resource', '页面资源'), width: '35%', render: (_, row) => {
          const item = metadata.get(keyOf(row));
          return <Space orientation="vertical" size={2}>
            <span>{item?.pages.map(code => label(catalog![code].title, code)).join(' / ') || t('field-scopes.editor.legacy', '已有配置')}</span>
            {(!item || item.pages.some(code => catalog![code].models.length > 1)) &&
              <Typography.Text type="secondary">{row.resource.split('.').pop()}</Typography.Text>}
          </Space>;
        }},
        {title: t('field-scopes.title.entry.field', '字段'), render: (_, row) => <Space wrap>
          {label(metadata.get(keyOf(row))?.title ?? '', row.field)}<Tag>{row.field}</Tag>
        </Space>},
        {title: t('field-scopes.title.entry.access', '访问级别'), width: 150, render: (_, row) =>
          <Select aria-label={`${row.field} ${t('field-scopes.title.entry.access', '访问级别')}`} value={row.access}
            options={accessOptions} style={{width: '100%'}} disabled={disabled || !metadata.has(keyOf(row))}
            onChange={access => onChange?.(value.map(entry => keyOf(entry) === keyOf(row) ? {...entry, access} : entry))} />},
        {title: '', width: 56, render: (_, row) => <Button type="text" danger icon={<DeleteOutlined />}
          aria-label={t('field-scopes.editor.remove', '移除字段')} disabled={disabled}
          onClick={() => onChange?.(value.filter(entry => keyOf(entry) !== keyOf(row)))} />},
      ]} />
  </div>;
}
