import {
  FullscreenExitOutlined,
  FullscreenOutlined,
  InboxOutlined,
  ReloadOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import {Button, Space, Table as AntTable, Tooltip} from 'antd';
import type {TableProps as AntTableProps} from 'antd';
import type {ColumnType, ColumnsType} from 'antd/es/table';
import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import ColumnSettings from '../Table/ColumnSettings';
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

export type DataTableProps<T> = AntTableProps<T> & {
  /** Stable preference key. If omitted, one is derived from the route and column keys. */
  storageKey?: string;
  /** Set to false for presentation-only tables that must not expose user settings. */
  settings?: boolean;
  /** Optional refresh action rendered in the unified table toolbar. */
  refresh?: () => void;
  toolbarExtra?: React.ReactNode;
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

const hashText = (value: string): string => {
  let hash = 2166136261;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(36);
};

const getUserId = (): string => {
  try {
    const raw = sessionStorage.getItem('sp.userinfo');
    if (raw) {
      const user = JSON.parse(raw) as Record<string, unknown>;
      const id = user.sub ?? user.id ?? user.username ?? user.preferred_username;
      if (id != null) return String(id);
    }
  } catch { /* use anonymous scope */ }
  return 'anonymous';
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
    scroll,
    loading,
    ...rest
  } = props;

  const rootRef = useRef<HTMLDivElement>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);

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

  const preferenceKey = useMemo(() => {
    const route = typeof window === 'undefined' ? 'server' : window.location.pathname;
    const tableKey = storageKey ?? `auto.${route}.${hashText(preferenceSignature)}`;
    return `sp.data-table.${getUserId()}.${tableKey}`;
  }, [preferenceSignature, storageKey]);

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

  const [columnSettings, setColumnSettings] = useState<ColumnSetting[]>(defaultColumnSettings);
  const [displaySettings, setDisplaySettings] = useState<TableDisplaySettings>(defaultDisplaySettings);
  const skipNextPersist = useRef(true);

  useEffect(() => {
    skipNextPersist.current = true;
    let nextColumns = defaultColumnSettings;
    let nextDisplay = defaultDisplaySettings;
    try {
      const raw = localStorage.getItem(preferenceKey);
      if (raw) {
        const saved = JSON.parse(raw) as {
          columns?: ColumnSetting[];
          display?: Partial<TableDisplaySettings>;
        };
        nextColumns = mergeColumnSettings(defaultColumnSettings, saved.columns);
        nextDisplay = normalizeTableDisplaySettings(saved.display, defaultDisplaySettings);
      }
    } catch { /* ignore invalid or unavailable browser storage */ }
    setColumnSettings(nextColumns);
    setDisplaySettings(nextDisplay);
  }, [defaultColumnSettings, defaultDisplaySettings, preferenceKey]);

  useEffect(() => {
    if (skipNextPersist.current) {
      skipNextPersist.current = false;
      return;
    }
    try {
      const persistedColumns = columnSettings.map(item => ({...item, fixed: item.fixed ?? null}));
      localStorage.setItem(preferenceKey, JSON.stringify({columns: persistedColumns, display: displaySettings}));
    } catch { /* browser storage may be disabled */ }
  }, [columnSettings, displaySettings, preferenceKey]);

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

  const normalizedPagination = useMemo(() => {
    if (pagination === false) return false;
    const configured = pagination && typeof pagination === 'object' ? pagination : {};
    return {
      showTotal: (total: number) => t('table.total', '共 {total} 条', {total}),
      ...configured,
      showSizeChanger: displaySettings.showSizeChanger,
      showQuickJumper: displaySettings.showQuickJumper,
    };
  }, [displaySettings.showQuickJumper, displaySettings.showSizeChanger, pagination, t]);

  const hasFixedColumns = columnSettings.some(item => item.visible && item.fixed);
  const showToolbar = settings || Boolean(refresh) || Boolean(toolbarExtra);

  return (
    <div ref={rootRef} className="sp-data-table-root">
      {showToolbar && (
        <div className="sp-data-table-toolbar">
          <div className="sp-data-table-toolbar-extra">{toolbarExtra}</div>
          <Space size={4}>
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
      <AntTable<T>
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
        columns={columns}
        loading={loading}
        locale={{...locale, emptyText: locale?.emptyText ?? emptyText}}
        pagination={normalizedPagination}
        scroll={hasFixedColumns ? {...scroll, x: scroll?.x ?? 'max-content'} : scroll}
        size={displaySettings.size}
        sticky={displaySettings.stickyHeader ? sticky : false}
      />
      <ColumnSettings
        open={settingsOpen}
        settings={columnSettings}
        displaySettings={displaySettings}
        onSave={(nextColumns, nextDisplay) => {
          setColumnSettings(nextColumns);
          setDisplaySettings(nextDisplay);
        }}
        onClose={() => setSettingsOpen(false)}
        onReset={() => {
          try { localStorage.removeItem(preferenceKey); } catch { /* ignore */ }
          setColumnSettings(defaultColumnSettings);
          setDisplaySettings(defaultDisplaySettings);
        }}
      />
    </div>
  );
};

export default DataTable;
