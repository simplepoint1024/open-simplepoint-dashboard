import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState} from 'react';
import type {TableRowSelection} from 'antd/es/table/interface';
import {App as AntApp, Button, Col, Row, Space, Table as AntTable, Tag, Tooltip, type ButtonProps, type TableProps as AntTableProps} from 'antd';
import {
  ClearOutlined,
  FilterFilled,
  FilterOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
  InboxOutlined,
  ReloadOutlined,
  SettingOutlined,
} from '@simplepoint/shared/types/icon';
import type {ColumnType, ColumnsType} from 'antd/es/table';
import {Resizable} from 'react-resizable';
import 'react-resizable/css/styles.css';
import {RJSFSchema} from '@rjsf/utils';
import {Page, toPagination} from '@simplepoint/shared/types/request';
import {createIcon} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {isRowSelectionClick} from './rowClickSelection';
import {usePreference, preferenceUser} from '@simplepoint/shared/preferences/usePreference';
import PreferenceFeedback from './PreferenceFeedback';
import {formatTemporal} from '@simplepoint/shared/format/temporal';
import TableActions from './Actions';
export {default as TableActions} from './Actions';
import ColumnFilter, {ColumnFilterType} from './ColumnFilter';
import {parseColumnFilter} from './filters';
import QueryTools from './QueryTools';
import {pageSizeOptions} from './pagination';
import {visibleKeySignature} from './visibleKeys';
import {encodeDocument, readDocument, validateView, type QueryContract} from './query';
import {
  ColumnFixed,
  ColumnSetting,
  DEFAULT_TABLE_DISPLAY_SETTINGS,
  TableDisplaySettings,
  normalizeColumnSettings,
} from './settings';
import {MAX_TABLE_COLUMN_WIDTH, normalizeTableDisplaySettings} from './settings';

import CardView, {type CardViewConfig} from './CardView';
import ViewToggle from './ViewToggle';
import {useCardLayout} from './useCardLayout';

const ColumnSettings = React.lazy(() => import('./ColumnSettings'));

const MIN_COLUMN_WIDTH = 80;
const DEFAULT_ICON_COLUMN_WIDTH = 80;
const DEFAULT_NUMBER_COLUMN_WIDTH = 120;
const DEFAULT_TEXT_COLUMN_WIDTH = 150;


export type TableButtonProps = ButtonProps & {
  key: string;
  sort: number;
  argumentMaxSize?: number;
  argumentMinSize?: number;
  text?: string;
  iconOnly?: boolean;
  color?: string;
  variant?: string;
};

export interface TableProps<T> {
  cards?: CardViewConfig | false;
  refresh: () => void;
  pageable: Page<T>;
  schema: RJSFSchema | any[];
  loading?: boolean;
  refreshDisabled?: boolean;
  columnOverrides?: Record<string, Partial<ColumnType<T>> & { order?: number }>;
  filters?: Record<string, string>;
  onFilterChange?: (filters: Record<string, string>) => void;
  sorter?: string | string[];
  onQueryChange?: (filters: Record<string, string>, sort?: string | string[]) => void;
  /** Opt-in for large flat pages; never enabled for expandable or variable-height rows. */
  virtual?: boolean;
  onChange?: (pagination: any, filters?: any, sorter?: any, extra?: any) => void;
  rowSelection?: { selectedKeys?: React.Key[]; isRowSelectable?: (row: T) => boolean };
  /** Successful owner mutations can invalidate a selection without remounting table preferences. */
  selectionResetKey?: number;
  onSelectionChange?: (selectedRowKeys: React.Key[], selectedRows: T[]) => void;
  onRowDoubleClick?: (record: T, key: React.Key) => void;
  onRowClick?: (record: T, key: React.Key) => void;
  onButtonEvents?: Record<string, (selectedRowKeys: React.Key[], selectedRows: T[], props: TableButtonProps) => void>;
  isButtonDisabled?: (
    button: TableButtonProps,
    selectedRowKeys: React.Key[],
    selectedRows: T[],
  ) => boolean;
  buttons?: TableButtonProps[]
  storageKey?: string;
  expandable?: AntTableProps<T>['expandable'];
  /** Row controls follow the first visible business column after user column preferences. */
  rowLeadingExtra?: (record: T) => React.ReactNode;
}

const resolveI18nLabel = (value: unknown): string => {
  if (typeof value !== 'string' || !value.startsWith('i18n:')) return String(value ?? '');
  const key = value.slice(5);
  const t = typeof window !== 'undefined' ? (window as any)?.spI18n?.t : undefined;
  return t?.(key, key) ?? key;
};

const normalizeOptionValue = (value: any) => value == null ? '' : String(value);

const schemaScalarType = (schemaDef: any): string | undefined => {
  const type = schemaDef?.type;
  if (Array.isArray(type)) {
    return type.find((item: unknown): item is string => typeof item === 'string' && item !== 'null');
  }
  return typeof type === 'string' ? type : undefined;
};

const resolveOptionLabel = (schemaDef: any, value: any): string | undefined => {
  const optionSchema = Array.isArray(schemaDef?.items) ? undefined : schemaDef?.items;
  const source = schemaScalarType(schemaDef) === 'array' && optionSchema ? optionSchema : schemaDef;
  const options = Array.isArray(source?.oneOf)
    ? source.oneOf
    : Array.isArray(source?.anyOf)
      ? source.anyOf
      : [];

  if (!options.length) {
    return undefined;
  }

  const matched = options.find((option: any) => {
    const optionValue = option?.const ?? option?.value;
    return normalizeOptionValue(optionValue) === normalizeOptionValue(value);
  });

  if (!matched) {
    return undefined;
  }

  return resolveI18nLabel(matched.title ?? matched.label ?? matched.const ?? value);
};

const resolveDictionaryCode = (schemaDef: any): string | undefined => {
  const code = schemaDef?.['x-dictionary-code']
    ?? schemaDef?.['x-ui']?.dictCode
    ?? schemaDef?.['x-ui']?.['dict-code'];
  return typeof code === 'string' && code.trim() ? code.trim() : undefined;
};

const resolveDictionaryOptions = (schemaDef: any): Array<{value: string; label: string}> => {
  const source = schemaScalarType(schemaDef) === 'array' && schemaDef?.items && !Array.isArray(schemaDef.items)
    ? schemaDef.items
    : schemaDef;
  const options = Array.isArray(source?.oneOf)
    ? source.oneOf
    : Array.isArray(source?.anyOf)
      ? source.anyOf
      : [];
  return options
    .filter((option: any) => option?.const != null || option?.value != null)
    .map((option: any) => ({
      value: normalizeOptionValue(option?.const ?? option?.value),
      label: resolveI18nLabel(option?.title ?? option?.label ?? option?.const ?? option?.value),
    }));
};

const normalizeTreeRows = <T extends object>(rows: T[]): T[] => {
  let changed = false;

  const normalize = (row: T): T => {
    const anyRow = row as any;
    if (!anyRow || typeof anyRow !== 'object' || !Array.isArray(anyRow.children)) {
      return row;
    }

    const normalizedChildren = normalizeTreeRows(anyRow.children);
    if (normalizedChildren.length === 0) {
      changed = true;
      const {children: _children, ...rest} = anyRow;
      return rest as T;
    }

    if (normalizedChildren !== anyRow.children) {
      changed = true;
      return {...anyRow, children: normalizedChildren};
    }

    return row;
  };

  const normalizedRows = rows.map(normalize);
  return changed ? normalizedRows : rows;
};

const toNumberWidth = (width: unknown): number | undefined => {
  if (typeof width === 'number' && Number.isFinite(width)) return width;
  if (typeof width !== 'string') return undefined;
  const matched = width.trim().match(/^(\d+(?:\.\d+)?)px$/);
  if (!matched) return undefined;
  const parsed = Number(matched[1]);
  return Number.isFinite(parsed) ? parsed : undefined;
};

const ResizableTitle = (props: React.HTMLAttributes<HTMLTableCellElement> & { onResize?: (e: React.SyntheticEvent, data: { size: { width: number; height: number } }) => void; width?: number }) => {
  const {onResize, width, ...restProps} = props;
  if (!width || !onResize) return <th {...restProps} />;
  return (
    <Resizable
      width={width}
      height={0}
      handle={
        <span
          className="react-resizable-handle sp-table-resize-handle"
          onClick={e => e.stopPropagation()}
        />
      }
      onResize={onResize as any}
      draggableOpts={{enableUserSelectHack: false}}
    >
      <th {...restProps} />
    </Resizable>
  );
};

const App = <T extends object = any>(props: TableProps<T>) => {
  const {t, locale} = useI18n();
  const contract = (props.schema as any)?.['x-query'] as QueryContract | undefined;
  const {message} = AntApp.useApp();
  const sortList = useMemo(() => props.sorter ? (Array.isArray(props.sorter) ? props.sorter : [props.sorter]) : [], [props.sorter]);
  const [filters, setFilters] = useState<Record<string, string>>(props.filters ?? {});

  // ── 列配置持久化类型 ───────────────────────────────────────────────────────
  type StoredColConfig = { visible: boolean; fixed?: ColumnFixed | null; order?: number };

  // ── 动态计算表格体可滚动高度 ─────────────────────────────────────────────────
  const containerRef = useRef<HTMLDivElement>(null);
  const toolbarRef   = useRef<HTMLDivElement>(null);
  const [scrollY, setScrollY] = useState<number>(400);

  useLayoutEffect(() => {
    const update = () => {
      const ct = containerRef.current;
      const tb = toolbarRef.current;
      if (!ct) return;
      const containerH = ct.getBoundingClientRect().height;
      const toolbarH   = tb ? tb.getBoundingClientRect().height + 16 /* margin-bottom */ : 54;
      // AntD table: thead ≈ 39px；pagination bar ≈ 56px (32px + 24px margin)
      setScrollY(Math.max(160, containerH - toolbarH - 39 - 56));
    };
    const ro = new ResizeObserver(update);
    if (containerRef.current) ro.observe(containerRef.current);
    if (toolbarRef.current) ro.observe(toolbarRef.current);
    update();
    return () => ro.disconnect();
  }, []);

  useEffect(() => {
    setFilters(props.filters ?? {});
  }, [props.filters]);

  const properties = useMemo(() => {
    const s = props.schema;
    if (Array.isArray(s)) {
      return (s as any[]).reduce((acc, cur) => {
        const key = (cur as any).name ?? (cur as any).key ?? (cur as any).id;
        if (key) acc[key] = cur;
        return acc;
      }, {} as Record<string, any>);
    }
    return (s as any)?.properties ?? s ?? {};
  }, [props.schema]);

  const columnIdentity = useMemo(() => visibleKeySignature(properties), [properties]);
  const visibleKeys = useMemo<string[]>(() => JSON.parse(columnIdentity), [columnIdentity]);

  const user = preferenceUser();
  const columnsPreference = usePreference<Record<string, StoredColConfig>>(
    props.storageKey ? `sp.table.cols.${props.storageKey}` : undefined, {}, raw => {
      if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {};
      return Object.fromEntries(Object.entries(raw).filter(([, v]) => typeof v === 'boolean' || v && typeof v === 'object')
        .map(([key, value]) => {
          if (typeof value === 'boolean') return [key, {visible: value}];
          const stored = value as Partial<StoredColConfig>;
          return [key, {visible: typeof stored.visible === 'boolean' ? stored.visible : true,
            ...('fixed' in stored ? {fixed: stored.fixed === 'left' || stored.fixed === 'right' ? stored.fixed : null} : {}),
            ...(Number.isSafeInteger(stored.order) && stored.order! >= 0 ? {order: stored.order} : {})}];
        }));
    }, props.storageKey ? `sp.table.cols.${user}.${props.storageKey}` : undefined);
  const widthsPreference = usePreference<Record<string, number>>(
    props.storageKey ? `sp.table.widths.${props.storageKey}` : undefined, {}, raw =>
      raw && typeof raw === 'object' && !Array.isArray(raw) ? Object.fromEntries(Object.entries(raw)
        .filter(([, width]) => typeof width === 'number' && Number.isFinite(width) && width >= MIN_COLUMN_WIDTH)) : {},
    props.storageKey ? `sp.table.widths.${user}.${props.storageKey}` : undefined);
  const displayPreference = usePreference(props.storageKey ? `sp.table.display.${props.storageKey}` : undefined,
    DEFAULT_TABLE_DISPLAY_SETTINGS, raw => normalizeTableDisplaySettings(raw as Partial<TableDisplaySettings>),
    props.storageKey ? `sp.table.display.${user}.${props.storageKey}` : undefined);
  const {value: colConfigs, setValue: setColConfigs} = columnsPreference;
  const {value: colWidths, setValue: setColWidths} = widthsPreference;
  const {value: displaySettings, setValue: setDisplaySettings} = displayPreference;
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);

  // Build ColumnSetting[] for the settings drawer (sorted by user order)
  const settingsItems = useMemo<ColumnSetting[]>(() => {
    const entries = visibleKeys.map((key, schemaIdx) => {
      const sd: any = properties[key] || {};
      const rawLabel = sd.title ?? key;
      const cfg = colConfigs[key];
      const configuredFixed = props.columnOverrides?.[key]?.fixed;
      const defaultFixed: ColumnFixed = configuredFixed === true || configuredFixed === 'left'
        ? 'left'
        : configuredFixed === 'right' ? 'right' : undefined;
      const hasStoredFixed = Boolean(cfg && Object.prototype.hasOwnProperty.call(cfg, 'fixed'));
      return {
        key,
        label: resolveI18nLabel(rawLabel),
        visible: cfg?.visible ?? true,
        fixed: hasStoredFixed ? (cfg?.fixed ?? undefined) : defaultFixed,
        width: colWidths[key],
        _order: typeof cfg?.order === 'number' ? cfg.order : schemaIdx,
      };
    });
    entries.sort((a, b) => a._order - b._order);
    return normalizeColumnSettings(entries.map(({_order: _, ...rest}) => rest));
  }, [visibleKeys, properties, colConfigs, colWidths, props.columnOverrides]);

  const handleSettingsSave = useCallback((items: ColumnSetting[], nextDisplaySettings: TableDisplaySettings) => {
    const next: Record<string, StoredColConfig> = {};
    const nextWidths: Record<string, number> = {};
    items.forEach((item, idx) => {
      next[item.key] = {visible: item.visible, fixed: item.fixed ?? null, order: idx};
      if (typeof item.width === 'number') nextWidths[item.key] = item.width;
    });
    setColConfigs(next);
    setColWidths(nextWidths);
    setDisplaySettings(nextDisplaySettings);
  }, [setColConfigs, setColWidths, setDisplaySettings]);

  const handleSettingsReset = () => {
    columnsPreference.reset(); widthsPreference.reset(); displayPreference.reset();
  };

  const handleResize = useCallback((key: string) => (_: React.SyntheticEvent, {size}: {size: {width: number; height: number}}) => {
    const width = Math.max(MIN_COLUMN_WIDTH, Math.round(size.width));
    setColWidths(prev => ({...prev, [key]: width}));
  }, [setColWidths]);

  useEffect(() => {
    const handleFullscreenChange = () => setIsFullscreen(document.fullscreenElement === containerRef.current);
    document.addEventListener('fullscreenchange', handleFullscreenChange);
    return () => document.removeEventListener('fullscreenchange', handleFullscreenChange);
  }, []);

  const toggleFullscreen = useCallback(() => {
    const container = containerRef.current;
    if (!container || typeof document === 'undefined') return;
    if (document.fullscreenElement === container) {
      void document.exitFullscreen().catch(() => { /* browser may deny fullscreen */ });
    } else {
      void container.requestFullscreen().catch(() => { /* browser may deny fullscreen */ });
    }
  }, []);

  const columns = useMemo<ColumnsType<T>>(() => {
    const entries = Object.entries(properties);
    const generated: Array<{ order: number; column: ColumnType<T> }> = entries
      .filter(([key]) => {
        const cfg = colConfigs[key];
        return cfg ? cfg.visible : visibleKeys.includes(key);
      })
      .map(([key, schemaDef]) => {
        const baseTitle = (schemaDef as any)?.title ?? key;
        const scalarType = schemaScalarType(schemaDef);
        const isBoolean = scalarType === 'boolean';
        const isNumber = scalarType === 'number' || scalarType === 'integer' || !!(schemaDef as any)?.['x-decimal'];
        const align = key === 'icon' ? 'center' : (isNumber ? 'right' : undefined);
        const dictionaryCode = resolveDictionaryCode(schemaDef);
        const resolvedOptions = resolveDictionaryOptions(schemaDef);
        const hasOptions = resolvedOptions.length > 0;
        const dictionaryOptions = dictionaryCode || hasOptions ? resolvedOptions : undefined;

        const renderCell = ['date', 'date-time', 'time'].includes((schemaDef as any)?.format)
          ? (val: unknown) => formatTemporal(val, {locale, kind: (schemaDef as any).format === 'date' ? 'date' : (schemaDef as any).format === 'time' ? 'time' : 'datetime'})
          : isBoolean
          ? (val: any) => {
            const active = val === true || val === 1 || val === 'true' || val === '1';
            return (
              <Tag
                color={active ? 'success' : 'default'}
                style={{margin: 0, fontSize: 11, lineHeight: '18px', padding: '0 6px', borderRadius: 3}}
              >
                {active ? t('common.yes', '是') : t('common.no', '否')}
              </Tag>
            );
          }
          : key === 'icon'
            ? (val: any) => (
              <span style={{display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: '100%'}}>
                {typeof val === 'string' ? createIcon(val) : null}
              </span>
             )
            : hasOptions
              ? (val: any) => Array.isArray(val)
                ? val.map(item => (
                  <Tag key={normalizeOptionValue(item)} style={{marginInlineEnd: 4}}>
                    {resolveOptionLabel(schemaDef, item) ?? String(item)}
                  </Tag>
                ))
                : resolveOptionLabel(schemaDef, val) ?? val
             : undefined;

        const textEllipsisRender = (!displaySettings.wrapText && !isBoolean && key !== 'icon' && !hasOptions)
          ? (val: any) => {
              if (val === null || val === undefined || val === '') return null;
              const str = String(val);
              if (str.length <= 30) return str;
              return (
                <Tooltip title={str} placement="topLeft">
                  <span style={{display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: '100%'}}>
                    {str}
                  </span>
                </Tooltip>
              );
            }
          : undefined;

        // 推断列类型，传给 ColumnFilter 以决定操作符分组
        const schemaType = (schemaDef as any)?.type;
        const columnFilterType: ColumnFilterType = (() => {
          if ((schemaDef as any)?.['x-decimal'] || contract?.fields[key]?.javaType === 'BigDecimal') return 'number';
          if (schemaType === 'number') return 'number';
          if (schemaType === 'integer') return 'integer';
          if (schemaType === 'boolean') return 'boolean';
          if ((schemaDef as any)?.format === 'date-time' || (schemaDef as any)?.format === 'date') return 'date';
          if (typeof schemaType === 'string') return 'string';
          if (Array.isArray(schemaType)) {
            const nonNull = schemaType.find((t: string) => t !== 'null');
            if (nonNull === 'number' || nonNull === 'integer') return nonNull as ColumnFilterType;
            if (nonNull === 'boolean') return 'boolean';
            if (nonNull === 'string') return 'string';
          }
          return 'unknown';
        })();

        const isActive = Boolean(filters[key]);
        const capability = contract?.fields[key];
        const structuredFilter = readDocument(filters[key]);
        const sortDirection = sortList.find(s => s.split(',')[0] === key)?.split(',')[1];

        const defaultWidth = isBoolean || key === 'icon'
          ? DEFAULT_ICON_COLUMN_WIDTH
          : isNumber ? DEFAULT_NUMBER_COLUMN_WIDTH : DEFAULT_TEXT_COLUMN_WIDTH;

        const column: ColumnType<T> = {
          title: baseTitle,
          dataIndex: key,
          key,
          align,
          sorter: contract ? (capability?.sortable ? {multiple: sortList.some(s => s.split(',')[0] === key) ? 100 - sortList.findIndex(s => s.split(',')[0] === key) : 1} : false) : true,
          showSorterTooltip: false,
          sortOrder: sortDirection === 'asc' ? 'ascend' : sortDirection === 'desc' ? 'descend' : null,
          ...((!displaySettings.wrapText && !isBoolean && key !== 'icon') ? {ellipsis: true} : {}),
        };

        (column as any).filterDropdown = ({close}: any) => (
          <div onKeyDown={(e) => e.stopPropagation()} onMouseDown={(e) => e.stopPropagation()}
               onClick={(e) => e.stopPropagation()}>
            <ColumnFilter
              initialOp={structuredFilter?.op ?? parseColumnFilter(filters[key]).op}
              initialValue={structuredFilter?.value}
              operators={capability?.operators}
              javaType={capability?.javaType}
              format={(schemaDef as any)?.format}
              initialText={parseColumnFilter(filters[key]).text}
              columnType={columnFilterType}
              columnLabel={typeof baseTitle === 'string' ? baseTitle : key}
              options={dictionaryOptions}
              onChange={(op: string, text: string, typedValue) => {
                // is:null / is:not:null 不需要 text
                const isNullOp = op === 'is:null' || op === 'is:not:null';
                const value = (isNullOp || text) ? capability
                  ? encodeDocument({field: key, op, ...(isNullOp ? {} : {value: typedValue})})
                  : `${op}${text ? `:${text}` : ''}` : '';
                const next = {...filters};
                if (value) next[key] = value; else delete next[key];
                if (contract) {
                  try {validateView({name: 'header', filters: next, sort: props.sorter}, contract);}
                  catch (e) {void message.error((e as Error).message); return;}
                }
                setFilters(next);
                if (props.onFilterChange) props.onFilterChange(next);
                else props.refresh();
                try { close?.(); } catch { /* ignore */ }
              }}
            />
          </div>
        );

        // 激活时换成实心图标 + 主色，未激活时细线图标 + 半透明
        (column as any).filterIcon = () => isActive
          ? <FilterFilled style={{color: '#1677ff', fontSize: 13}}/>
          : <FilterOutlined style={{opacity: 0.4, fontSize: 12}}/>;

        if (contract && !capability?.operators.length) {
          column.filterDropdown = undefined;
          column.filterIcon = undefined;
        }
        column.render = (renderCell ?? textEllipsisRender) as ColumnType<T>['render'];

        const override = props.columnOverrides?.[key] as (Partial<ColumnType<T>> & { order?: number }) | undefined;
        const {order: overrideOrder, ...overrideRest} = override || {};
        const overrideOnHeaderCell = overrideRest.onHeaderCell;
        const overrideWidth = toNumberWidth(overrideRest.width);
        const requestedWidth = colWidths[key] ?? overrideWidth ?? defaultWidth;
        const columnWidth = typeof requestedWidth === 'number' && Number.isFinite(requestedWidth)
          ? Math.max(MIN_COLUMN_WIDTH, Math.min(MAX_TABLE_COLUMN_WIDTH, Math.round(requestedWidth)))
          : defaultWidth;

        // User config order takes precedence over schema/override order
        const userOrder = colConfigs[key]?.order;
        const columnConfig = colConfigs[key];
        const hasUserFixed = Boolean(columnConfig && Object.prototype.hasOwnProperty.call(columnConfig, 'fixed'));
        const userFixed = columnConfig?.fixed ?? undefined;
        const finalOrder = typeof userOrder === 'number'
          ? userOrder
          : typeof overrideOrder === 'number' ? overrideOrder : Number.MAX_SAFE_INTEGER;

        return {
          order: finalOrder,
          column: {
            ...column,
            ...overrideRest,
            ...(hasUserFixed ? {fixed: userFixed} : {}),
            ...(displaySettings.wrapText ? {ellipsis: false} : {}),
            key,
            dataIndex: key,
            width: columnWidth,
            onHeaderCell: (col: ColumnType<T>) => ({
              ...((overrideOnHeaderCell as any)?.(col) ?? {}),
              width: columnWidth,
              onResize: handleResize(key),
            }),
          },
        };
      });

    return generated
      .sort((left, right) => {
        const fixedRank = (fixed: ColumnType<T>['fixed']) => (
          fixed === true || fixed === 'left' ? 0 : fixed === 'right' ? 2 : 1
        );
        return fixedRank(left.column.fixed) - fixedRank(right.column.fixed)
          || left.order - right.order;
      })
      .map((item, index) => {
        if (index !== 0 || !props.rowLeadingExtra) return item.column;
        const original = item.column.render;
        return {...item.column, render: (value: any, record: T, rowIndex: number) => {
          const extra = props.rowLeadingExtra?.(record);
          const rendered = original ? original(value, record, rowIndex) : value;
          if (!extra) return rendered;
          const wrap = (content: React.ReactNode) => <Space size={4} wrap>
              <PreferenceFeedback preferences={[columnsPreference, widthsPreference, displayPreference]}/>{content}{extra}</Space>;
          if (rendered && typeof rendered === 'object' && !React.isValidElement(rendered)
            && !('$$typeof' in rendered) && 'children' in rendered) {
            return {...rendered, children: wrap(rendered.children)};
          }
          return wrap(rendered as React.ReactNode);
        }};
      });
  }, [properties, colConfigs, visibleKeys, filters, contract, sortList, colWidths, displaySettings.wrapText, handleResize, props.onFilterChange, props.refresh, props.columnOverrides, props.rowLeadingExtra, t, locale])

  const anonKeyMapRef = useRef(new WeakMap<object, number>());
  const anonKeySeqRef = useRef(1);
  const keyOfRecord = useCallback((record: T): React.Key => {
    const anyRec: any = record as any;
    const k = anyRec.id ?? anyRec.key;
    if (k !== undefined && k !== null) return k as React.Key;
    const map = anonKeyMapRef.current;
    let n = map.get(anyRec as object);
    if (!n) {
      n = anonKeySeqRef.current++;
      map.set(anyRec as object, n);
    }
    return `~${n}`;
  }, []);

  const rawDataSource = props.pageable?.content ?? [];
  const dataSource = useMemo(
    () => props.expandable ? rawDataSource : normalizeTreeRows(rawDataSource),
    [props.expandable, rawDataSource],
  );
  const rowByKey = useMemo(() => {
    const rowsByKey = new Map<React.Key, T>();
    const visit = (rows: T[]) => {
      rows.forEach((row) => {
        rowsByKey.set(keyOfRecord(row), row);
        const children = (row as any)?.children;
        if (Array.isArray(children) && children.length) {
          visit(children);
        }
      });
    };
    visit(dataSource);
    return rowsByKey;
  }, [dataSource, keyOfRecord]);

  const pagination = {
    ...toPagination(props.pageable),
    pageSizeOptions: pageSizeOptions(contract?.maxPageSize),
    showSizeChanger: displaySettings.showSizeChanger,
    showQuickJumper: displaySettings.showQuickJumper,
    showTotal: (total: number) => t('table.total', '共 {total} 条', {total}),
  };

  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>(props.rowSelection?.selectedKeys ?? []);
  const [selectedRows, setSelectedRows] = useState<T[]>([]);
  const previousSelectionReset = useRef(props.selectionResetKey);
  useEffect(() => {
    if (previousSelectionReset.current === props.selectionResetKey) return;
    previousSelectionReset.current = props.selectionResetKey;
    setSelectedRowKeys([]); setSelectedRows([]); props.onSelectionChange?.([], []);
  }, [props.selectionResetKey, props.onSelectionChange]);

  useEffect(() => {
    if (props.rowSelection?.selectedKeys) {
      setSelectedRowKeys(props.rowSelection.selectedKeys);
    }
  }, [props.rowSelection?.selectedKeys]);

  const getSelectedRowsForEvent = useCallback(() => {
    if (!selectedRowKeys.length) return [];
    const currentRows = selectedRowKeys
      .map((key) => rowByKey.get(key))
      .filter((row): row is T => !!row);
    return currentRows;
  }, [rowByKey, selectedRowKeys, selectedRows]);

  const onSelectChange = useCallback(
    (keys: React.Key[], rows: T[]) => {
      setSelectedRowKeys(keys);
      setSelectedRows(rows);
      props.onSelectionChange?.(keys, rows);
    },
    [props],
  );

  const rowSelection: TableRowSelection<T> = {
    selectedRowKeys,
    getCheckboxProps: row => ({disabled: props.rowSelection?.isRowSelectable?.(row) === false}),
    onChange: (keys: React.Key[], rows: T[]) => onSelectChange(keys, rows),
  };

  useEffect(() => {
    if (props.loading || !selectedRowKeys.length) return;
    const visibleKeys = selectedRowKeys.filter(key => rowByKey.has(key));
    if (visibleKeys.length !== selectedRowKeys.length) {
      onSelectChange(visibleKeys, visibleKeys.map(key => rowByKey.get(key)!));
    }
  }, [props.loading, rowByKey, selectedRowKeys, onSelectChange]);

  const emptyText = useMemo(() => (
    <div className="sp-table-empty">
      <InboxOutlined className="sp-table-empty-icon" />
      <div className="sp-table-empty-title">{t('table.emptyText', '暂无数据')}</div>
    </div>
  ), [t]);

  const cardsSupported = props.cards !== false && !props.expandable
    && !dataSource.some(record => Array.isArray((record as any).children) && (record as any).children.length);
  const viewMode = cardsSupported ? displaySettings.viewMode : 'table';
  const changeCardPage = (current: number, pageSize: number) => {
    onSelectChange([], []);
    props.onChange?.({current, pageSize}, {}, undefined, {action: 'paginate'});
  };
  const cardLayout = useCardLayout(containerRef, viewMode, cardsSupported, displaySettings.cardRows,
    pagination.current ?? 1, pagination.pageSize ?? 10, pagination.total ?? 0, changeCardPage,
    contract?.maxPageSize, props.cards ? props.cards.minWidth : undefined);

  return (
    <div ref={containerRef} className="sp-table-root" style={{height: '100%', display: 'flex', flexDirection: 'column'}}>
      <div ref={toolbarRef} className="sp-table-toolbar">
        <Row justify="space-between" align="middle" className="sp-table-toolbar-row">
          <Col className="sp-table-toolbar-main">
            <Space className="sp-table-button-group" size={[8, 8]} wrap>
              <TableActions buttons={props.buttons} selectedKeys={selectedRowKeys} selectedRows={getSelectedRowsForEvent()}
                onButtonEvents={props.onButtonEvents} isButtonDisabled={props.isButtonDisabled} />
              {selectedRowKeys.length > 0 && (
                <Tag
                  className="sp-table-selected-tag"
                  color="blue"
                  closable
                  onClose={() => { setSelectedRowKeys([]); setSelectedRows([]); props.onSelectionChange?.([], []); }}
                >
                  {t('table.selectedCount', '已选 {count} 条', {count: selectedRowKeys.length})}
                </Tag>
              )}
            </Space>
          </Col>
          <Col className="sp-table-toolbar-actions">
            <Space size={4} wrap>
              <PreferenceFeedback preferences={[columnsPreference, widthsPreference, displayPreference]}/>
              {cardsSupported && <ViewToggle mode={viewMode} rows={displaySettings.cardRows}
                onMode={viewMode => setDisplaySettings(previous => ({...previous, viewMode}))}
                onRows={cardRows => setDisplaySettings(previous => ({...previous, cardRows}))} />}
              {contract?.version === 1 && props.onQueryChange && <QueryTools contract={contract} properties={properties}
                filters={filters} sort={props.sorter} storageKey={props.storageKey} onApply={props.onQueryChange} />}
              {Object.keys(filters).length > 0 && (
                <Tooltip title={t('table.clearFilters', '清除全部筛选')}>
                  <Button
                    aria-label={t('table.clearFilters', '清除全部筛选')}
                    className="sp-table-icon-button"
                    type="text"
                    icon={<ClearOutlined/>}
                    onClick={() => {
                      setFilters({});
                      if (props.onFilterChange) props.onFilterChange({});
                      else props.refresh();
                    }}
                  />
                </Tooltip>
              )}
              <Tooltip title={t('table.refresh', '刷新')}>
                <Button
                  aria-label={t('table.refresh', '刷新')}
                  className="sp-table-icon-button"
                  type="text"
                  icon={<ReloadOutlined/>}
                  onClick={() => props.refresh()}
                  loading={props.loading}
                  disabled={props.refreshDisabled}
                />
              </Tooltip>
              <Tooltip title={isFullscreen ? t('table.exitFullscreen', '退出全屏') : t('table.fullscreen', '全屏')}>
                <Button
                  aria-label={isFullscreen ? t('table.exitFullscreen', '退出全屏') : t('table.fullscreen', '全屏')}
                  className="sp-table-icon-button"
                  icon={isFullscreen ? <FullscreenExitOutlined/> : <FullscreenOutlined/>}
                  type="text"
                  onClick={toggleFullscreen}
                />
              </Tooltip>
              <Tooltip title={t('table.settings.title', '表格设置')}>
                <Button
                  aria-label={t('table.settings.title', '表格设置')}
                  className="sp-table-icon-button"
                  icon={<SettingOutlined/>}
                  type="text"
                  onClick={() => setSettingsOpen(true)}
                />
              </Tooltip>
            </Space>
          </Col>
        </Row>
      </div>
      <div style={{flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column'}}>
        {viewMode === 'cards' ? <CardView<T> data={dataSource} columns={columns}
          columnsCount={cardLayout.columns} config={props.cards || undefined} rowKey={keyOfRecord}
          selection={rowSelection} loading={props.loading} emptyText={emptyText}
          pagination={{...pagination, onChange: changeCardPage}}
          onRow={record => ({onClick: event => {
            if (!isRowSelectionClick(event) || props.rowSelection?.isRowSelectable?.(record) === false) return;
            const key = keyOfRecord(record);
            const keys = selectedRowKeys.includes(key) ? selectedRowKeys.filter(item => item !== key) : [...selectedRowKeys, key];
            onSelectChange(keys, keys.map(item => rowByKey.get(item)).filter((row): row is T => !!row));
            props.onRowClick?.(record, key);
          }, onDoubleClick: event => {
            if (!isRowSelectionClick(event) || props.rowSelection?.isRowSelectable?.(record) === false) return;
            onSelectChange([keyOfRecord(record)], [record]);
            props.onRowDoubleClick?.(record, keyOfRecord(record));
          }})}
          actions={record => <TableActions appearance="row" buttons={props.buttons?.filter(button => (button.argumentMinSize ?? 0) === 1
            && (button.argumentMaxSize == null || button.argumentMaxSize >= 1))}
            selectedKeys={[keyOfRecord(record)]} selectedRows={[record]} onButtonEvents={props.onButtonEvents}
            isButtonDisabled={props.isButtonDisabled} />}
        /> : <AntTable<T>
          className={[
            'sp-table-fill',
            displaySettings.striped && 'sp-table-striped',
            displaySettings.wrapText && 'sp-table-wrap',
            !displaySettings.rowHover && 'sp-table-no-hover',
          ].filter(Boolean).join(' ')}
          bordered={displaySettings.bordered}
          columns={columns}
          dataSource={dataSource}
          expandable={props.expandable}
          loading={{spinning: !!props.loading, delay: 200}}
          pagination={pagination}
          size={displaySettings.size}
          sticky={displaySettings.stickyHeader}
          rowKey={keyOfRecord}
          onChange={props.onChange}
          onRow={record => ({
            onClick: event => {
              if (!isRowSelectionClick(event) || props.rowSelection?.isRowSelectable?.(record) === false) return;
              const key = keyOfRecord(record);
              const keys = selectedRowKeys.includes(key)
                ? selectedRowKeys.filter(selected => selected !== key) : [...selectedRowKeys, key];
              onSelectChange(keys, keys.map(selected => rowByKey.get(selected)).filter((row): row is T => !!row));
              props.onRowClick?.(record, key);
            },
            onDoubleClick: event => {
              if (!props.onRowDoubleClick || props.rowSelection?.isRowSelectable?.(record) === false) return;
              const target = event.target as HTMLElement | null;
              if (target?.closest('button, a, input, textarea, select, [role="button"], .ant-checkbox-wrapper')) {
                return;
              }
              const key = keyOfRecord(record);
              onSelectChange([key], [record]);
              props.onRowDoubleClick?.(record, key);
            },
          })}
          rowSelection={rowSelection}
          virtual={props.virtual === true && !props.expandable && !displaySettings.wrapText && dataSource.length >= 100}
          scroll={{y: scrollY, x: props.virtual === true && !props.expandable && !displaySettings.wrapText && dataSource.length >= 100
            ? columns.reduce((width, column) => width + (typeof column.width === 'number' ? column.width : 150), 48) : 'max-content'}}
          locale={{emptyText}}
          components={{header: {cell: ResizableTitle}}}
        />}
      </div>
      {settingsOpen && <React.Suspense fallback={null}><ColumnSettings
        open={settingsOpen}
        settings={settingsItems}
        displaySettings={displaySettings}
        onSave={handleSettingsSave}
        onClose={() => setSettingsOpen(false)}
        onReset={handleSettingsReset}
      /></React.Suspense>}
    </div>
  );
};

export default App;
