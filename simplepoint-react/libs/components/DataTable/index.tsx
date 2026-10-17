import {newestFirst} from './defaultOrder';
import {RowActionContext} from '../Table/Actions';
import {
  FullscreenExitOutlined,
  FullscreenOutlined,
  InboxOutlined,
  ReloadOutlined,
  SettingOutlined,
} from '@simplepoint/shared/types/icon';
import {Alert, Button, Space, Table as AntTable, Tooltip, Typography} from 'antd';
import type {TableProps as AntTableProps} from 'antd';
import type {ColumnType, ColumnsType} from 'antd/es/table';
import type {Key} from 'react';
import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import CardView, {type CardViewConfig} from '../Table/CardView';
import ViewToggle from '../Table/ViewToggle';
import {useCardLayout} from '../Table/useCardLayout';
import {isRowSelectionClick} from '../Table/rowClickSelection';
import {usePreference, preferenceUser, tablePreferenceIdentity} from '@simplepoint/shared/preferences/usePreference';
import {usePageLocation} from '@simplepoint/shared/page/hooks';
import PreferenceFeedback from '../Table/PreferenceFeedback';
import {pageSizeOptions} from '../Table/pagination';
import {useTableQuery, type DataTableQuery} from './useTableQuery';
export type {DataTableQuery} from './useTableQuery';
export type {LocalField, LocalFields, QuerySchema, TableQueryState} from './localQuery';
import {
  type ColumnFixed,
  type ColumnSetting,
  DEFAULT_FIXED_COLUMN_WIDTH,
  DEFAULT_TABLE_DISPLAY_SETTINGS,
  type TableDisplaySettings,
  mergeColumnSettings,
  normalizeColumnSettings,
  normalizeTableDisplaySettings,
} from '../Table/settings';

const ColumnSettings = React.lazy(() => import('../Table/ColumnSettings'));


export type DataTableProps<T> = AntTableProps<T> & {
  cards?: CardViewConfig | false;
  selectable?: boolean;
  /** Preserve SQL/result or domain sequence order; remote pages are always owned by the server. */
  defaultOrder?: 'newest' | 'provided';
  /** Stable preference key. If omitted, one is derived from the route and column keys. */
  storageKey?: string;
  /** Set to false for presentation-only tables that must not expose user settings. */
  settings?: boolean;
  /** Optional refresh action rendered in the unified table toolbar. */
  refresh?: () => void;
  toolbarExtra?: React.ReactNode;
  /** scroll.y includes the platform toolbar in height-constrained result panes. */
  includeToolbarInScroll?: boolean;
  /** Fill a height-constrained parent; only rows scroll, while toolbar/header/pagination stay visible. */
  fitContainer?: boolean;
  /** Query ownership is explicit: a server page is never filtered locally. */
  query?: DataTableQuery;
};
export type {ColumnsType} from 'antd/es/table';

type ConfiguredColumn<T> = {
  key: string;
  column: ColumnType<T>;
  label: string;
};

const toColumnKey = <T extends object>(column: ColumnType<T>, index: number): string => {
  if (column.key != null) return String(column.key);
  if (Array.isArray(column.dataIndex)) return column.dataIndex.map(String).join('.');
  if (column.dataIndex != null) return String(column.dataIndex);
  return `column-${index}`;
};

const toColumnLabel = <T extends object>(column: ColumnType<T>, key: string): string => {
  if (typeof column.title === 'string' || typeof column.title === 'number') return String(column.title);
  return key;
};

const toColumnWidth = (width: unknown): number | undefined => {
  if (typeof width === 'number' && Number.isFinite(width)) return width;
  if (typeof width !== 'string') return undefined;
  const match = width.trim().match(/^(\d+(?:\.\d+)?)px$/);
  return match ? Number(match[1]) : undefined;
};

const toFixed = (fixed: ColumnType<Record<string, unknown>>['fixed']): ColumnFixed => {
  if (fixed === true || fixed === 'left') return 'left';
  if (fixed === 'right') return 'right';
  return undefined;
};

/**
 * Platform table for feature-specific and in-memory data sets.
 *
 * Standard CRUD screens should continue to use SimpleTable. DataTable keeps
 * Ant Design's flexible dataSource/columns API while adding the same user-level
 * column, appearance, pagination, empty-state, and fullscreen behavior.
 */
const DataTable = <T extends object = Record<string, unknown>>(
  props: DataTableProps<T>,
) => {
  const {t} = useI18n();
  const {
    cards,
    defaultOrder = 'newest',
    selectable = true,
    bordered = true,
    className,
    columns: sourceColumns = [],
    locale,
    pagination,
    refresh,
    settings = true,
    size = 'middle',
    sticky = true,
    storageKey,
    toolbarExtra,
    includeToolbarInScroll = false,
    fitContainer = false,
    scroll,
    loading,
    query,
    dataSource = [],
    onChange,
    ...rest
  } = props;

  const rootRef = useRef<HTMLDivElement>(null);
  const toolbarRef = useRef<HTMLDivElement>(null);
  const [localPage, setLocalPage] = useState({current: 1, pageSize: 10});
  const [toolbarHeight, setToolbarHeight] = useState(42);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [localSelectedKeys, setLocalSelectedKeys] = useState<Key[]>([]);

  const configuredColumns = useMemo<ConfiguredColumn<T>[]>(() => (
    (sourceColumns as ColumnType<T>[]).map((column, index) => {
      const key = toColumnKey(column, index);
      return {key, column, label: toColumnLabel(column, key)};
    })
  ), [sourceColumns]);
  const columnDefinitionSignature = configuredColumns
    .map(item => `${item.key}:${item.label}:${String(item.column.fixed ?? '')}:${String(item.column.width ?? '')}`)
    .join('|');
  const preferenceSignature = configuredColumns
    .map(item => item.key)
    .join('|');

  const pageLocation = usePageLocation();
  const preferenceKey = `sp.data-table.${tablePreferenceIdentity(storageKey, pageLocation, preferenceSignature)}`;
  // Legacy auto keys used pathname and are ambiguous under HashRouter; only explicit keys are migrated.
  const legacyPreferenceKey = storageKey ? `sp.data-table.${preferenceUser()}.${storageKey}` : undefined;

  const defaultColumnSettings = useMemo<ColumnSetting[]>(() => configuredColumns.map(item => ({
    key: item.key,
    label: item.label,
    visible: true,
    fixed: toFixed(item.column.fixed),
    width: toColumnWidth(item.column.width),
  // The signature avoids resetting preferences when callers construct columns inline.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  })), [columnDefinitionSignature]);

  const normalizedSize = size === 'medium' ? 'middle' : size;
  const defaultStickyHeader = Boolean(sticky);
  const defaultDisplaySettings = useMemo<TableDisplaySettings>(() => ({
    ...DEFAULT_TABLE_DISPLAY_SETTINGS,
    bordered,
    size: normalizedSize,
    stickyHeader: defaultStickyHeader,
  }), [bordered, defaultStickyHeader, normalizedSize]);

  const preferences = usePreference<{columns: ColumnSetting[]; display: TableDisplaySettings}>(settings || cards !== false ? preferenceKey : undefined,
    {columns: [], display: defaultDisplaySettings}, raw => {
      const saved = raw && typeof raw === 'object' ? raw as {columns?: ColumnSetting[]; display?: Partial<TableDisplaySettings>} : {};
      return {columns: Array.isArray(saved.columns) ? saved.columns.filter(item => item && typeof item.key === 'string') : [],
        display: normalizeTableDisplaySettings(saved.display, defaultDisplaySettings)};
    }, legacyPreferenceKey);
  const columnSettings = useMemo(() => mergeColumnSettings(defaultColumnSettings, preferences.value.columns),
    [defaultColumnSettings, preferences.value.columns]);
  const displaySettings = preferences.value.display;
  const setDisplaySettings = (next: React.SetStateAction<TableDisplaySettings>) => preferences.setValue(previous => ({...previous,
    display: typeof next === 'function' ? next(previous.display) : next}));

  useEffect(() => {
    const onFullscreenChange = () => setIsFullscreen(document.fullscreenElement === rootRef.current);
    document.addEventListener('fullscreenchange', onFullscreenChange);
    return () => document.removeEventListener('fullscreenchange', onFullscreenChange);
  }, []);

  const toggleFullscreen = useCallback(() => {
    const root = rootRef.current;
    if (!root) return;
    if (document.fullscreenElement === root) {
      void document.exitFullscreen().catch(() => { /* browser may deny fullscreen */ });
    } else {
      void root.requestFullscreen().catch(() => { /* browser may deny fullscreen */ });
    }
  }, []);

  const columns = useMemo<ColumnsType<T>>(() => {
    const sourceByKey = new Map(configuredColumns.map(item => [item.key, item]));
    return normalizeColumnSettings(columnSettings)
      .filter(setting => setting.visible)
      .map(setting => {
        const source = sourceByKey.get(setting.key);
        if (!source) return undefined;
        return {
          ...source.column,
          ...(['actions', 'action', 'operations', 'operation'].includes(source.key) && source.column.render ? {
            render: (value: any, record: T, index: number) => {
              const rendered = source.column.render!(value, record, index);
              const wrap = (content: React.ReactNode) => <RowActionContext.Provider value={true}>{content}</RowActionContext.Provider>;
              if (rendered && typeof rendered === 'object' && !React.isValidElement(rendered)
                && !('$$typeof' in rendered) && 'children' in rendered) {
                return {...rendered, children: wrap(rendered.children)};
              }
              return wrap(rendered as React.ReactNode);
            },
          } : {}),
          key: setting.key,
          fixed: setting.fixed,
          width: setting.width ?? source.column.width ?? (setting.fixed ? DEFAULT_FIXED_COLUMN_WIDTH : undefined),
          ...(displaySettings.wrapText ? {ellipsis: false} : {}),
        } as ColumnType<T>;
      })
      .filter((column): column is ColumnType<T> => Boolean(column));
  }, [columnSettings, configuredColumns, displaySettings.wrapText]);

  const emptyText = useMemo(() => (
    <div className="sp-table-empty">
      <InboxOutlined className="sp-table-empty-icon" />
      <div className="sp-table-empty-title">
        {t('table.emptyText', '暂无数据')}
      </div>
    </div>
  ), [t]);

  const orderedData = useMemo(() => defaultOrder === 'provided' || query?.mode === 'remote'
    || query?.mode === 'local' && query.scope === 'result'
    || pagination && typeof pagination.total === 'number' && pagination.total > dataSource.length
    ? dataSource : newestFirst(dataSource, configuredColumns.map(item => item.key)), [dataSource, defaultOrder, query, pagination, configuredColumns]);
  const queryController = useTableQuery(query, columns, orderedData, preferenceKey, sourceColumns);
  const sourceSelection = props.rowSelection;
  const selectedKeys = sourceSelection?.selectedRowKeys ?? localSelectedKeys;
  const keyOfRecord = useCallback((record: T): Key => {
    if (typeof props.rowKey === 'function') return props.rowKey(record);
    return (record as Record<string, unknown>)[String(props.rowKey ?? 'key')] as Key;
  }, [props.rowKey]);
  const rowByKey = useMemo(() => new Map(queryController.data.map(record => [keyOfRecord(record), record])),
    [keyOfRecord, queryController.data]);
  useEffect(() => {
    if ((typeof loading === 'object' ? loading.spinning : loading)
      || sourceSelection?.preserveSelectedRowKeys || !selectedKeys.length) return;
    const visibleKeys = selectedKeys.filter(key => rowByKey.has(key));
    if (visibleKeys.length !== selectedKeys.length) {
      setLocalSelectedKeys(visibleKeys);
      sourceSelection?.onChange?.(visibleKeys, visibleKeys.map(key => rowByKey.get(key)!), {type: 'none'});
    }
  }, [loading, sourceSelection, selectedKeys, rowByKey]);
  const toggleRow = useCallback((record: T) => {
    if (sourceSelection?.getCheckboxProps?.(record).disabled) return;
    const key = keyOfRecord(record);
    const next = selectedKeys.includes(key)
      ? selectedKeys.filter(selected => selected !== key)
      : sourceSelection?.type === 'radio' ? [key] : [...selectedKeys, key];
    setLocalSelectedKeys(next);
    sourceSelection?.onChange?.(next, next.map(selected => rowByKey.get(selected))
      .filter((row): row is T => !!row), {type: 'single'});
  }, [keyOfRecord, rowByKey, selectedKeys, sourceSelection]);
  const incompleteSnapshot = query?.mode === 'local' && pagination && typeof pagination === 'object'
    && (pagination.total ?? 0) > dataSource.length;

  const normalizedPagination = useMemo(() => {
    if (pagination === false) return false;
    const configured = pagination && typeof pagination === 'object' ? pagination : {};
    const pageSize = queryController.size ?? configured.pageSize ?? configured.defaultPageSize ?? 10;
    const cardLocal = displaySettings.viewMode === 'cards' && query?.mode !== 'remote'
      && (configured.total ?? dataSource.length) <= queryController.data.length;
    return {
      showTotal: (total: number) => t('table.total', '共 {total} 条', {total}),
      current: localPage.current,
      pageSize: localPage.pageSize,
      ...configured,
      pageSizeOptions: pageSizeOptions(query?.mode === 'remote' ? query.schema['x-query'].maxPageSize : undefined,
        configured.pageSizeOptions),
      ...(query?.mode === 'local' ? {pageSize, total: queryController.data.length,
        current: Math.min(queryController.page, Math.max(1, Math.ceil(queryController.data.length / pageSize)))} : {}),
      ...(cardLocal ? {pageSize: queryController.size ?? localPage.pageSize,
        total: queryController.data.length,
        current: Math.min(query?.mode === 'local' ? queryController.page : localPage.current,
          Math.max(1, Math.ceil(queryController.data.length / (queryController.size ?? localPage.pageSize))))} : {}),
      showSizeChanger: displaySettings.showSizeChanger,
      showQuickJumper: displaySettings.showQuickJumper,
    };
  }, [displaySettings.viewMode, displaySettings.showQuickJumper, displaySettings.showSizeChanger, dataSource.length, pagination, t,
    query, queryController.size, queryController.data.length, queryController.page, localPage]);

  const cardsSupported = cards !== false && !props.expandable && !props.components
    && !sourceColumns.some(column => 'children' in column || (!query && (column.sorter || column.filters || column.filterDropdown)))
    && !dataSource.some(record => Array.isArray((record as any).children) && (record as any).children.length);
  const viewMode = cardsSupported ? displaySettings.viewMode : 'table';
  const currentPage = normalizedPagination ? normalizedPagination.current ?? 1 : 1;
  const currentSize = normalizedPagination ? normalizedPagination.pageSize ?? 10 : dataSource.length;
  const total = normalizedPagination ? normalizedPagination.total ?? queryController.data.length : queryController.data.length;
  const remotePage = query?.mode === 'remote' || total > queryController.data.length;
  const cardData = pagination === false || remotePage ? queryController.data
    : queryController.data.slice((currentPage - 1) * currentSize, currentPage * currentSize);
  const changeCardPage = (current: number, pageSize: number) => {
    setLocalPage({current, pageSize});
    setLocalSelectedKeys([]);
    sourceSelection?.onChange?.([], [], {type: 'none'});
    const page = {current, pageSize};
    const extra = {action: 'paginate' as const, currentDataSource: [...queryController.data]};
    queryController.onChange(page, {}, {}, extra);
    onChange?.(page, {}, {}, extra);
    if (pagination && typeof pagination === 'object') pagination.onChange?.(current, pageSize);
  };
  const cardLayout = useCardLayout(rootRef, viewMode, cardsSupported && pagination !== false,
    displaySettings.cardRows, currentPage, currentSize, total, changeCardPage,
    query?.mode === 'remote' ? query.schema['x-query'].maxPageSize : undefined,
    cards ? cards.minWidth : undefined);

  const hasFixedColumns = columnSettings.some(item => item.visible && item.fixed);
  const showToolbar = cardsSupported || settings || Boolean(refresh) || Boolean(toolbarExtra) || Boolean(queryController.tools);
  useEffect(() => {
    const toolbar = toolbarRef.current;
    if (!includeToolbarInScroll || !toolbar || typeof ResizeObserver === 'undefined') return;
    const measure = () => setToolbarHeight(toolbar.getBoundingClientRect().height
      + (parseFloat(getComputedStyle(toolbar).marginBottom) || 0));
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(toolbar);
    return () => observer.disconnect();
  }, [includeToolbarInScroll, showToolbar]);
  const effectiveScroll = {
    ...scroll,
    ...((fitContainer || isFullscreen) && scroll?.y == null ? {y: '100%'} : {}),
    ...(hasFixedColumns ? {x: scroll?.x ?? 'max-content'} : {}),
    ...(includeToolbarInScroll && showToolbar && typeof scroll?.y === 'number'
      ? {y: Math.max(40, scroll.y - toolbarHeight)} : {}),
  };

  return (
    <div ref={rootRef} className={['sp-data-table-root', fitContainer && 'sp-data-table-fit'].filter(Boolean).join(' ')} data-query-mode={query?.mode}>
      {showToolbar && (
        <div ref={toolbarRef} className="sp-data-table-toolbar">
          <div className="sp-data-table-toolbar-extra">
            {toolbarExtra}
            {queryController.scopeHint && <Typography.Text type="secondary" style={{fontSize: 12}}>{queryController.scopeHint}</Typography.Text>}
          </div>
          <Space size={4} wrap>
            <PreferenceFeedback preferences={[preferences]}/>
            {cardsSupported && <ViewToggle mode={viewMode} rows={displaySettings.cardRows} pagination={pagination !== false}
              onMode={viewMode => setDisplaySettings(previous => ({...previous, viewMode}))}
              onRows={cardRows => setDisplaySettings(previous => ({...previous, cardRows}))} />}
            {queryController.tools}
            {refresh && (
              <Tooltip title={t('table.refresh', '刷新')}>
                <Button
                  aria-label={t('table.refresh', '刷新')}
                  className="sp-table-icon-button"
                  type="text"
                  icon={<ReloadOutlined/>}
                  loading={Boolean(loading)}
                  onClick={refresh}
                />
              </Tooltip>
            )}
            {settings && configuredColumns.length > 0 && (
              <>
                <Tooltip title={isFullscreen ? t('table.exitFullscreen', '退出全屏') : t('table.fullscreen', '全屏')}>
                  <Button
                    aria-label={isFullscreen ? t('table.exitFullscreen', '退出全屏') : t('table.fullscreen', '全屏')}
                    className="sp-table-icon-button"
                    type="text"
                    icon={isFullscreen ? <FullscreenExitOutlined/> : <FullscreenOutlined/>}
                    onClick={toggleFullscreen}
                  />
                </Tooltip>
                <Tooltip title={t('table.settings.title', '表格设置')}>
                  <Button
                    aria-label={t('table.settings.title', '表格设置')}
                    className="sp-table-icon-button"
                    type="text"
                    icon={<SettingOutlined/>}
                    onClick={() => setSettingsOpen(true)}
                  />
                </Tooltip>
              </>
            )}
          </Space>
        </div>
      )}
      {(queryController.error || incompleteSnapshot) && <Alert type="error" showIcon
        title={t('table.loadFail', '加载失败')}
        description={incompleteSnapshot ? t('table.query.incompleteSnapshot', '此数据不是完整结果集，请使用服务端查询。') : queryController.error}
        action={<Button onClick={queryController.reset}>{t('table.query.reset', '重置筛选与排序')}</Button>} />}
      {viewMode === 'cards' ? <CardView<T> data={incompleteSnapshot ? [] : cardData}
        columns={queryController.columns} columnsCount={cardLayout.columns} rowKey={keyOfRecord}
        config={cards || undefined} loading={typeof loading === 'object' ? loading.spinning : loading}
        emptyText={typeof locale?.emptyText === 'function' ? locale.emptyText() : locale?.emptyText ?? emptyText}
        pagination={normalizedPagination && {...normalizedPagination, total, onChange: changeCardPage}}
        selection={selectable ? {...sourceSelection, selectedRowKeys: selectedKeys, onChange: (keys, rows, info) => {
          setLocalSelectedKeys(keys); sourceSelection?.onChange?.(keys, rows, info);
        }} : undefined}
        onRow={(record, index) => {
          const configured = props.onRow?.(record, index);
          return {...configured, onClick: event => {
            configured?.onClick?.(event as React.MouseEvent<HTMLTableRowElement>);
            if (selectable && isRowSelectionClick(event)) toggleRow(record);
          }};
        }}
      /> : <AntTable<T>
        {...rest}
        bordered={displaySettings.bordered}
        className={[
          'sp-data-table',
          'sp-table-fill',
          displaySettings.striped && 'sp-table-striped',
          displaySettings.wrapText && 'sp-table-wrap',
          !displaySettings.rowHover && 'sp-table-no-hover',
          className,
        ].filter(Boolean).join(' ')}
        columns={queryController.columns}
        dataSource={incompleteSnapshot ? [] : queryController.data}
        rowSelection={selectable ? {...sourceSelection, selectedRowKeys: selectedKeys,
          onChange: (keys, rows, info) => {
            setLocalSelectedKeys(keys);
            sourceSelection?.onChange?.(keys, rows, info);
          }} : undefined}
        onRow={(record, index) => {
          const configured = props.onRow?.(record, index);
          return {...configured, onClick: event => {
            configured?.onClick?.(event);
            if (selectable && isRowSelectionClick(event)) toggleRow(record);
          }};
        }}
        onChange={(page, filters, sorter, extra) => {
          if (extra.action === 'paginate') setLocalPage({current: page.current ?? 1, pageSize: page.pageSize ?? 10});
          if (query) queryController.onChange(page, filters, sorter, extra);
          if (!query || extra.action === 'paginate') onChange?.(page, filters, sorter, extra);
        }}
        loading={loading}
        locale={{...locale, emptyText: locale?.emptyText ?? emptyText}}
        pagination={normalizedPagination}
        scroll={effectiveScroll}
        size={displaySettings.size}
        sticky={displaySettings.stickyHeader ? sticky : false}
      />}
      {settingsOpen && <React.Suspense fallback={null}><ColumnSettings
        open={settingsOpen}
        settings={columnSettings}
        displaySettings={displaySettings}
        onSave={(nextColumns, nextDisplay) => {
          preferences.setValue({columns: nextColumns.map(item => ({...item, fixed: item.fixed ?? null})) as ColumnSetting[], display: nextDisplay});
        }}
        onClose={() => setSettingsOpen(false)}
        onReset={preferences.reset}
      /></React.Suspense>}
    </div>
  );
};

export default Object.assign(DataTable, {
  SELECTION_ALL: AntTable.SELECTION_ALL,
  SELECTION_INVERT: AntTable.SELECTION_INVERT,
  SELECTION_NONE: AntTable.SELECTION_NONE,
});
