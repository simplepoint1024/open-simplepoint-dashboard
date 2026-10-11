import {Alert, Button, Empty, Pagination, Radio, Space, Spin, Typography} from 'antd';
import Table from '@simplepoint/components/DataTable';
import {DownOutlined, RightOutlined, FolderOutlined, FileOutlined, createIcon} from '@simplepoint/shared/types/icon';
import TableActions from '@simplepoint/components/Table/Actions';
import {useEffect, useMemo, useRef, useState, type ReactNode} from 'react';
import {fetchFieldResourceNodes, fetchPageFields, type FieldResourceNode, type FieldScopeEntryDto} from '@/api/system/field-scope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {permissionLabels, accessTypes} from '@simplepoint/shared/i18n/permissionLabels';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageActive} from '@simplepoint/shared/page/hooks';
import './index.css';

const keyOf = (entry: Pick<FieldScopeEntryDto, 'resource' | 'field'>) => `${entry.resource}#${entry.field}`;
const EMPTY: FieldScopeEntryDto[] = [];

// Compact hierarchy navigation owns a counted 15-node page per branch; TreeTable is a data grid, not this sidebar.
// These controls only navigate locally. Rule writes and retries remain platform TableActions/SForm operations.
function ResourceBranch({parentId, selected, choose, disabled, canInteract, depth = 0}: {
  parentId?: string; selected?: string; choose: (node: FieldResourceNode) => void; disabled?: boolean; canInteract: () => boolean; depth?: number;
}) {
  const {t, ensure, locale} = useI18n();
  const [page, setPage] = useState(1), pageRef = useRef(page); pageRef.current = page;
  const operation = useOperationScope(JSON.stringify([parentId, page])), active = usePageActive();
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const query = useOwnedData(['field-resource-tree', parentId, page], ({signal}) => fetchFieldResourceNodes(parentId, page - 1, signal),
    {enabled: !disabled, staleTime: 30_000, retry: false});
  const labels = useMemo(() => permissionLabels(t), [t]);
  const current = () => !disabled && active && operation.current() && operation.isSelected() && pageRef.current === page && canInteract();
  const expand = (id: string) => {
    if (!current()) return;
    setExpanded(previous => {const next = new Set(previous); next.has(id) ? next.delete(id) : next.add(id); return next;});
  };
  useEffect(() => {
    const titles = query.data?.content.map(row => row.title) ?? [];
    void ensure(['permissions', ...titles.filter(title => title.startsWith('i18n:')).map(title => title.slice(5).split('.')[0])]);
  }, [query.data, ensure, locale]);
  return <div role="group">
    {query.error && <Alert type="error" title={t('field-scopes.message.catalogFailed', '字段目录加载失败，请刷新后重试')}
      action={<TableActions selectedKeys={[]} selectedRows={[]}
        buttons={[{key: 'retry', sort: 0, text: t('table.retry', '重试')}]}
        onButtonEvents={{retry: () => {if (current()) void query.refetch({cancelRefetch: false});}}}/>} />}
    {query.isFetching && <Spin size="small" />}
    {query.data?.content.map(row => <div key={row.id}>
      <div className={`field-resource-node${selected === row.id ? ' is-selected' : ''}`} style={{paddingLeft: depth * 14 + 4}}>
        {row.hasChildren ? <Button type="text" size="small" icon={expanded.has(row.id) ? <DownOutlined /> : <RightOutlined />}
          disabled={disabled} aria-label={t('permissions.expand', '展开或收起资源')} onClick={() => expand(row.id)} /> : <span style={{width: 24, flexShrink: 0}} />}
        <button type="button" disabled={disabled} onClick={() => {if (current()) {if (row.pageCode) choose(row); else expand(row.id);}}}>
          {row.icon ? createIcon(row.icon) : row.hasChildren ? <FolderOutlined /> : <FileOutlined />}
          <span>{labels.title(row.title)}{row.pageCode && <small>{t('permissions.viewPoint', '查看权限')}</small>}</span>
        </button>
      </div>
      {row.hasChildren && expanded.has(row.id) && <ResourceBranch parentId={row.id} selected={selected} choose={choose} disabled={disabled} canInteract={canInteract} depth={depth + 1} />}
    </div>)}
    {!!query.data && query.data.page.totalElements > 15 && <Pagination simple size="small" current={page} pageSize={15}
      total={query.data.page.totalElements} showSizeChanger={false} disabled={disabled} onChange={next => {if (current()) {pageRef.current = next; setPage(next);}}} />}
    {!parentId && query.data && !query.data.content.length && <Empty description={t('field-scopes.editor.empty', '当前没有已授权且支持字段权限的页面资源')} />}
  </div>;
}

export default function FieldEntries({value = EMPTY, onChange, disabled, canInteract, basicInfo, readOnlyEntries, readOnlyAccess, allowNew = true}: {
  value?: FieldScopeEntryDto[]; onChange?: (entries: FieldScopeEntryDto[]) => void; disabled?: boolean; basicInfo?: ReactNode;
  canInteract: () => boolean; readOnlyEntries?: boolean; readOnlyAccess?: boolean; allowNew?: boolean;
}) {
  const {t, ensure, locale} = useI18n();
  const [selected, setSelected] = useState<FieldResourceNode>(), selectedRef = useRef(selected); selectedRef.current = selected;
  const entriesRef = useRef(value); entriesRef.current = value;
  const operation = useOperationScope(selected?.pageCode), active = usePageActive();
  const selectedPage = useOwnedData(['field-page-fields', selected?.pageCode], ({signal}) => fetchPageFields(selected!.pageCode!, signal),
    {enabled: !disabled && !!selected?.pageCode, staleTime: 30_000, retry: false});
  const labels = useMemo(() => permissionLabels(t), [t]);
  const current = () => !disabled && active && operation.current() && operation.isSelected() && canInteract();
  const choose = (node: FieldResourceNode) => {if (current()) {selectedRef.current = node; setSelected(node);}};
  const container = useRef<HTMLDivElement>(null);
  const [height, setHeight] = useState(360);
  useEffect(() => {
    if (!container.current) return;
    const observer = new ResizeObserver(([entry]) => setHeight(Math.max(120, entry.contentRect.height - 40)));
    observer.observe(container.current); return () => observer.disconnect();
  }, []);
  useEffect(() => {
    const titles = selectedPage.data?.models.flatMap(model => Object.values(model.properties).map(field => field.title)) ?? [];
    void ensure(['permissions', ...(selected?.pageCode ? [selected.pageCode.split('.')[0]] : []),
      ...titles.filter(title => title.startsWith('i18n:')).map(title => title.slice(5).split('.')[0])]);
  }, [selectedPage.data, selected?.pageCode, ensure, locale]);
  const configured = useMemo(() => new Map(value.map(entry => [keyOf(entry), entry])), [value]);
  const fieldMetadata = useMemo(() => selectedPage.data?.models.flatMap((model, group) => Object.entries(model.properties).map(([field, metadata]) => ({
    pageCode: selected?.pageCode,
    resource: model.model, field, title: labels.field(field, metadata, selected?.pageCode),
    group: selectedPage.data!.models.length > 1 ? labels.model(model.model, group) : '',
  }))) ?? [], [selectedPage.data, selected?.pageCode, labels]);
  // Editing access changes the queryable value, not the parsed/translated metadata index.
  const rows = useMemo(() => fieldMetadata.map(metadata => ({...metadata, metadata,
    accessName: labels.access(configured.get(keyOf(metadata))?.access ?? ''),
  })), [fieldMetadata, configured, labels]);
  const options = useMemo(() => accessTypes.map(access => ({value: access, label: labels.access(access)})), [labels]);
  const setAccess = (row: typeof rows[number], access?: string) => {
    const previous = entriesRef.current, exists = previous.some(entry => keyOf(entry) === keyOf(row));
    if (!current() || row.pageCode !== selectedRef.current?.pageCode || readOnlyEntries
      || access && (readOnlyAccess || !exists && (!allowNew || previous.length >= 500))) return;
    const next = previous.filter(entry => keyOf(entry) !== keyOf(row));
    if (access) next.push({resource: row.resource, field: row.field, access});
    entriesRef.current = next; onChange?.(next);
  };
  return <div className="field-permission-layout">
    <aside className="field-permission-sidebar">
      <div className="field-permission-basic">{basicInfo}</div>
      <strong>{t('permissions.resources', '资源目录')}</strong>
      <div className="field-permission-tree"><ResourceBranch selected={selected?.id} choose={choose} disabled={disabled} canInteract={canInteract} /></div>
      <Typography.Text type="secondary">{t('permissions.configuredCount', '已设置 {count} 个字段', {count: value.length})}</Typography.Text>
    </aside>
    <section className="field-permission-fields">
      <strong>{selected ? labels.title(selected.title) : t('field-scopes.title.entries', '字段规则')}</strong>
      <Typography.Text type="secondary">{t('permissions.fieldHint', '选择左侧资源后设置字段权限。未选择的字段沿用原有权限；切换资源会保留当前修改。')}</Typography.Text>
      {selectedPage.error && <Alert type="error" title={t('field-scopes.message.catalogFailed', '字段目录加载失败，请刷新后重试')}
        action={<TableActions selectedKeys={[]} selectedRows={[]}
          buttons={[{key: 'retry', sort: 0, text: t('table.retry', '重试')}]}
          onButtonEvents={{retry: () => {if (current() && selectedRef.current?.pageCode === selected?.pageCode) void selectedPage.refetch({cancelRefetch: false});}}}/>} />}
      <div className="field-permission-field-table" ref={container}>
        {!selected ? <Empty description={t('permissions.selectResource', '请从左侧选择资源权限点')} /> :
          <Table virtual size="small" rowKey={keyOf} dataSource={rows} loading={selectedPage.isFetching}
            settings={false} pagination={false}
            query={{mode: 'local', scope: 'complete', contextKey: selected.pageCode, fields: {
              title: {type: 'string'}, accessName: {type: 'string'},
            }}}
            scroll={{x: 680, y: height}} columns={[
              {title: t('field-scopes.title.entry.field', '字段'), dataIndex: 'title', width: 230, render: (_, row) => <Space orientation="vertical" size={0}>
                {row.title}{row.group && <Typography.Text type="secondary">{row.group}</Typography.Text>}
              </Space>},
              {title: t('field-scopes.title.entry.access', '访问级别'), dataIndex: 'accessName', width: 400, render: (_, row) =>
                <Radio.Group name={keyOf(row)} aria-label={row.title} value={configured.get(keyOf(row))?.access}
                  disabled={disabled || readOnlyEntries || readOnlyAccess || (!configured.has(keyOf(row)) && (!allowNew || value.length >= 500))}
                  options={options}
                  onChange={event => setAccess(row, event.target.value)} />},
              {title: '', width: 50, render: (_, row) => <TableActions selectedKeys={[keyOf(row)]} selectedRows={[row]}
                buttons={[{key: 'clearField', sort: 0, type: 'text', size: 'small', icon: 'UndoOutlined', iconOnly: true,
                  title: t('permissions.clearField', '清除字段设置'), argumentMinSize: 1, argumentMaxSize: 1}]}
                isButtonDisabled={() => disabled || readOnlyEntries || !configured.has(keyOf(row))}
                onButtonEvents={{clearField: () => setAccess(row)}}/>},
            ]} />}
      </div>
    </section>
  </div>;
}
