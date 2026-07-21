import React, {MouseEventHandler, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState} from 'react';
import type {TableRowSelection} from 'antd/es/table/interface';
import {Button, Col, Row, Space, Table as AntTable, Tag, Tooltip, type ButtonProps} from 'antd';
import {FilterFilled, FilterOutlined, InboxOutlined, ReloadOutlined, SettingOutlined} from '@ant-design/icons';
import type {ColumnType, ColumnsType} from 'antd/es/table';
import {Resizable} from 'react-resizable';
import 'react-resizable/css/styles.css';
import {RJSFSchema} from '@rjsf/utils';
import {Page, toPagination} from '@simplepoint/shared/types/request';
import {createIcon} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {get, put} from '@simplepoint/shared/api/methods';
import {request} from '@simplepoint/shared/api/client';
import ColumnFilter, {ColumnFilterType} from './ColumnFilter';
import ColumnSettings, {ColumnFixed, ColumnSetting} from './ColumnSettings';

const MIN_COLUMN_WIDTH = 80;
const DEFAULT_ICON_COLUMN_WIDTH = 80;
const DEFAULT_NUMBER_COLUMN_WIDTH = 120;
const DEFAULT_TEXT_COLUMN_WIDTH = 150;

const preferenceUrl = (key: string) => `/common/users/preferences?key=${encodeURIComponent(key)}`;

export type TableButtonProps = ButtonProps & {
  key: string;
  sort: number;
  argumentMaxSize?: number;
  argumentMinSize?: number;
  text?: string;
  color?: string;
  variant?: string;
};

export interface TableProps<T> {
  refresh: () => void;
  pageable: Page<T>;
  schema: RJSFSchema | any[];
  loading?: boolean;
  refreshDisabled?: boolean;
  columnOverrides?: Record<string, Partial<ColumnType<T>> & { order?: number }>;
  filters?: Record<string, string>;
  onFilterChange?: (filters: Record<string, string>) => void;
  sorter?: string; // Spring format: "field,asc" or "field,desc"
  onChange?: (pagination: any, filters?: any, sorter?: any, extra?: any) => void;
  rowSelection?: { selectedKeys?: React.Key[] };
  onSelectionChange?: (selectedRowKeys: React.Key[], selectedRows: T[]) => void;
  onButtonEvents?: Record<string, (selectedRowKeys: React.Key[], selectedRows: T[], props: TableButtonProps) => void>;
  buttons?: TableButtonProps[]
  storageKey?: string;
}

const parseOp = (stored?: string) => {
  if (!stored) return 'like';
  const idx = stored.indexOf(':');
  return idx === -1 ? stored : stored.slice(0, idx);
};
const parseText = (stored?: string) => {
  if (!stored) return '';
  const idx = stored.indexOf(':');
  return idx === -1 ? '' : stored.slice(idx + 1);
};

const parseVisibleTriState = (val: any): boolean | undefined => {
  if (val === true || val === 'true' || val === 1 || val === '1') return true;
  if (val === false || val === 'false' || val === 0 || val === '0') return false;
  return undefined;
};

const readVisibleFlag = (schema: any): boolean | undefined => {
  if (!schema) return undefined;
  const legacy1 = schema?.['x-ui']?.['x-list-visible'];
  return (
    parseVisibleTriState(legacy1)
  );
};

const computeVisibleKeys = (properties: Record<string, any>): string[] => {
  const keys = Object.keys(properties);
  let anyDeclared = false;
  const visible: string[] = [];
  for (const key of keys) {
    const flag = readVisibleFlag(properties[key]);
    if (flag !== undefined) {
      anyDeclared = true;
      if (flag) visible.push(key);
    }
  }
  return anyDeclared ? visible : keys;
};

const resolveI18nLabel = (value: unknown): string => {
  if (typeof value !== 'string' || !value.startsWith('i18n:')) return String(value ?? '');
  const key = value.slice(5);
  const t = typeof window !== 'undefined' ? (window as any)?.spI18n?.t : undefined;
  return t?.(key, key) ?? key;
};

const normalizeOptionValue = (value: any) => value == null ? '' : String(value);

const resolveOptionLabel = (schemaDef: any, value: any): string | undefined => {
  const options = Array.isArray(schemaDef?.oneOf)
    ? schemaDef.oneOf
    : Array.isArray(schemaDef?.anyOf)
      ? schemaDef.anyOf
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

/** Read a stable user identifier from the session cache written by useUserInfo(). */
function getUserId(): string {
  try {
    const raw = sessionStorage.getItem('sp.userinfo');
    if (raw) {
      const info = JSON.parse(raw) as Record<string, unknown>;
      const id = info?.sub ?? info?.id ?? info?.username ?? info?.preferred_username;
      if (id != null) return String(id);
    }
  } catch { /* ignore */ }
  return 'anonymous';
}

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
  const [filters, setFilters] = useState<Record<string, string>>(props.filters ?? {});

  // ── 列配置持久化类型 ───────────────────────────────────────────────────────
  type StoredColConfig = { visible: boolean; fixed?: ColumnFixed; order?: number };

  // 解析外部传入的排序状态，用于给对应列设置 sortOrder
  const [sortField, sortDir] = useMemo(() => {
    const s = props.sorter ?? '';
    const idx = s.lastIndexOf(',');
    if (!s) return [undefined, undefined];
    if (idx === -1) return [s, 'asc'];
    return [s.slice(0, idx), s.slice(idx + 1)];
  }, [props.sorter]);

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

  const visibleKeys = useMemo(() => computeVisibleKeys(properties), [properties]);

  // Keys are scoped by userId so each user gets independent settings on the same browser.
  const storageKey = useMemo(() => {
    if (!props.storageKey) return undefined;
    return `sp.table.cols.${getUserId()}.${props.storageKey}`;
  }, [props.storageKey]);

  const widthStorageKey = useMemo(() => {
    if (!props.storageKey) return undefined;
    return `sp.table.widths.${getUserId()}.${props.storageKey}`;
  }, [props.storageKey]);

  // Backend preference keys (userId is implicit — the server scopes by JWT sub)
  const apiColsKey = props.storageKey ? `sp.table.cols.${props.storageKey}` : undefined;
  const apiWidthsKey = props.storageKey ? `sp.table.widths.${props.storageKey}` : undefined;

  const [colConfigs, setColConfigs] = useState<Record<string, StoredColConfig>>({});
  const [settingsOpen, setSettingsOpen] = useState(false);

  // Debounce timers for backend saves
  const colSaveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const widthSaveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Skip initial-load triggers
  const colConfigsInitialized = useRef(false);
  const colWidthsInitialized = useRef(false);

  // Load from localStorage first (instant), then fetch backend (async override)
  useEffect(() => {
    colConfigsInitialized.current = false;
    const base: Record<string, StoredColConfig> = {};
    visibleKeys.forEach((k) => { base[k] = {visible: true}; });
    try {
      if (storageKey) {
        const raw = localStorage.getItem(storageKey);
        if (raw) {
          const saved = JSON.parse(raw) as Record<string, any>;
          const firstVal = saved[Object.keys(saved)[0]];
          const isOldFormat = typeof firstVal === 'boolean';
          visibleKeys.forEach((k) => {
            if (isOldFormat) {
              if (typeof saved[k] === 'boolean') base[k] = {visible: saved[k]};
            } else if (saved[k] && typeof saved[k] === 'object') {
              base[k] = saved[k] as StoredColConfig;
            }
          });
        }
      }
    } catch { /* ignore */ }
    setColConfigs(base);

    // Async override from backend
    if (apiColsKey) {
      get<string | undefined>(preferenceUrl(apiColsKey))
        .then(res => {
          const raw = res;
          if (!raw) return;
          const saved = JSON.parse(raw) as Record<string, any>;
          setColConfigs(prev => {
            const next = {...prev};
            visibleKeys.forEach((k) => {
              if (saved[k] && typeof saved[k] === 'object') next[k] = saved[k] as StoredColConfig;
            });
            return next;
          });
          if (storageKey) {
            try { localStorage.setItem(storageKey, raw); } catch { /* ignore */ }
          }
        })
        .catch(() => { /* silently ignore — use localStorage fallback */ })
        .finally(() => { colConfigsInitialized.current = true; });
    } else {
      colConfigsInitialized.current = true;
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visibleKeys, storageKey, apiColsKey]);

  // Persist to localStorage + debounce backend save whenever configs change
  useEffect(() => {
    if (!colConfigsInitialized.current) return;
    try {
      if (storageKey && Object.keys(colConfigs).length) {
        const payload: Record<string, StoredColConfig> = {};
        visibleKeys.forEach((k) => { if (colConfigs[k]) payload[k] = colConfigs[k]; });
        const json = JSON.stringify(payload);
        localStorage.setItem(storageKey, json);
        if (apiColsKey) {
          if (colSaveTimer.current) clearTimeout(colSaveTimer.current);
          colSaveTimer.current = setTimeout(() => {
            put(preferenceUrl(apiColsKey), {value: json})
              .catch(() => { /* ignore */ });
          }, 800);
        }
      }
    } catch { /* ignore */ }
  }, [colConfigs, visibleKeys, storageKey, apiColsKey]);

  // Build ColumnSetting[] for the settings drawer (sorted by user order)
  const settingsItems = useMemo<ColumnSetting[]>(() => {
    const entries = visibleKeys.map((key, schemaIdx) => {
      const sd: any = properties[key] || {};
      const rawLabel = sd.title ?? key;
      const cfg = colConfigs[key];
      return {
        key,
        label: resolveI18nLabel(rawLabel),
        visible: cfg?.visible ?? true,
        fixed: cfg?.fixed as ColumnFixed,
        _order: typeof cfg?.order === 'number' ? cfg.order : schemaIdx,
      };
    });
    entries.sort((a, b) => a._order - b._order);
    return entries.map(({_order: _, ...rest}) => rest);
  }, [visibleKeys, properties, colConfigs]);

  const handleSettingsSave = useCallback((items: ColumnSetting[]) => {
    const next: Record<string, StoredColConfig> = {};
    items.forEach((item, idx) => {
      next[item.key] = {visible: item.visible, fixed: item.fixed, order: idx};
    });
    setColConfigs(next);
  }, []);

  const handleSettingsReset = useCallback(() => {
    try {
      if (storageKey) localStorage.removeItem(storageKey);
      if (widthStorageKey) localStorage.removeItem(widthStorageKey);
    } catch { /* ignore */ }
    if (apiColsKey) {
      request(preferenceUrl(apiColsKey), {method: 'DELETE'})
        .catch(() => { /* ignore */ });
    }
    if (apiWidthsKey) {
      request(preferenceUrl(apiWidthsKey), {method: 'DELETE'})
        .catch(() => { /* ignore */ });
    }
    const next: Record<string, StoredColConfig> = {};
    visibleKeys.forEach((k) => { next[k] = {visible: true}; });
    setColConfigs(next);
    setColWidths({});
  }, [storageKey, widthStorageKey, apiColsKey, apiWidthsKey, visibleKeys]);

  const [colWidths, setColWidths] = useState<Record<string, number>>(() => {
    if (!props.storageKey) return {};
    try {
      const key = `sp.table.widths.${getUserId()}.${props.storageKey}`;
      const raw = localStorage.getItem(key);
      return raw ? (JSON.parse(raw) as Record<string, number>) : {};
    } catch { return {}; }
  });

  const handleResize = useCallback((key: string) => (_: React.SyntheticEvent, {size}: {size: {width: number; height: number}}) => {
    const width = Math.max(MIN_COLUMN_WIDTH, Math.round(size.width));
    setColWidths(prev => ({...prev, [key]: width}));
  }, []);

  // Async load column widths from backend (overrides localStorage if server has newer data)
  useEffect(() => {
    colWidthsInitialized.current = false;
    if (!apiWidthsKey) { colWidthsInitialized.current = true; return; }
    get<string | undefined>(preferenceUrl(apiWidthsKey))
      .then(res => {
        const raw = res;
        if (!raw) return;
        const saved = JSON.parse(raw) as Record<string, number>;
        setColWidths(saved);
        if (widthStorageKey) {
          try { localStorage.setItem(widthStorageKey, raw); } catch { /* ignore */ }
        }
      })
      .catch(() => { /* silently ignore */ })
      .finally(() => { colWidthsInitialized.current = true; });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [apiWidthsKey, widthStorageKey]);

  // Persist column widths whenever they change
  useEffect(() => {
    if (!colWidthsInitialized.current) return;
    if (!Object.keys(colWidths).length) return;
    try {
      if (widthStorageKey) localStorage.setItem(widthStorageKey, JSON.stringify(colWidths));
    } catch { /* ignore */ }
    if (apiWidthsKey) {
      if (widthSaveTimer.current) clearTimeout(widthSaveTimer.current);
      widthSaveTimer.current = setTimeout(() => {
        put(preferenceUrl(apiWidthsKey), {value: JSON.stringify(colWidths)})
          .catch(() => { /* ignore */ });
      }, 800);
    }
  }, [colWidths, widthStorageKey, apiWidthsKey]);

  const columns = useMemo<ColumnsType<T>>(() => {
    const entries = Object.entries(properties);
    const generated: Array<{ order: number; column: ColumnType<T> }> = entries
      .filter(([key]) => {
        const cfg = colConfigs[key];
        return cfg ? cfg.visible : visibleKeys.includes(key);
      })
      .map(([key, schemaDef]) => {
        const baseTitle = (schemaDef as any)?.title ?? key;
        const isBoolean = (schemaDef as any)?.type === 'boolean';
        const isNumber = (schemaDef as any)?.type === 'number' || (schemaDef as any)?.type === 'integer';
        const align = key === 'icon' ? 'center' : (isNumber ? 'right' : undefined);
        const hasOptions = Array.isArray((schemaDef as any)?.oneOf) || Array.isArray((schemaDef as any)?.anyOf);

        const renderCell = isBoolean
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
              ? (val: any) => resolveOptionLabel(schemaDef, val) ?? val
             : undefined;

        const textEllipsisRender = (!isBoolean && key !== 'icon' && !hasOptions)
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

        const defaultWidth = isBoolean || key === 'icon'
          ? DEFAULT_ICON_COLUMN_WIDTH
          : isNumber ? DEFAULT_NUMBER_COLUMN_WIDTH : DEFAULT_TEXT_COLUMN_WIDTH;

        const column: ColumnType<T> = {
          title: baseTitle,
          dataIndex: key,
          key,
          align,
          sorter: true,
          showSorterTooltip: false,
          sortOrder: key === sortField
            ? (sortDir === 'asc' ? 'ascend' : 'descend')
            : null,
          ...((!isBoolean && key !== 'icon') ? {ellipsis: true} : {}),
        };

        (column as any).filterDropdown = ({close}: any) => (
          <div onKeyDown={(e) => e.stopPropagation()} onMouseDown={(e) => e.stopPropagation()}
               onClick={(e) => e.stopPropagation()}>
            <ColumnFilter
              initialOp={parseOp(filters[key])}
              initialText={parseText(filters[key])}
              columnType={columnFilterType}
              columnLabel={typeof baseTitle === 'string' ? baseTitle : key}
              onChange={(op: string, text: string) => {
                // is:null / is:not:null 不需要 text
                const isNullOp = op === 'is:null' || op === 'is:not:null';
                const value = (isNullOp || text) ? `${op}${text ? `:${text}` : ''}` : '';
                const next = {...filters};
                if (value) next[key] = value; else delete next[key];
                setFilters(next);
                props.onFilterChange?.(next);
                props.refresh();
                try { close?.(); } catch { /* ignore */ }
              }}
            />
          </div>
        );

        // 激活时换成实心图标 + 主色，未激活时细线图标 + 半透明
        (column as any).filterIcon = () => isActive
          ? <FilterFilled style={{color: '#1677ff', fontSize: 13}}/>
          : <FilterOutlined style={{opacity: 0.4, fontSize: 12}}/>;

        column.render = (renderCell ?? textEllipsisRender) as ColumnType<T>['render'];

        const override = props.columnOverrides?.[key] as (Partial<ColumnType<T>> & { order?: number }) | undefined;
        const {order: overrideOrder, ...overrideRest} = override || {};
        const overrideOnHeaderCell = overrideRest.onHeaderCell;
        const overrideWidth = toNumberWidth(overrideRest.width);
        const columnWidth = colWidths[key] ?? overrideWidth ?? defaultWidth;

        // User config order takes precedence over schema/override order
        const userOrder = colConfigs[key]?.order;
        const userFixed = colConfigs[key]?.fixed;
        const finalOrder = typeof userOrder === 'number'
          ? userOrder
          : typeof overrideOrder === 'number' ? overrideOrder : Number.MAX_SAFE_INTEGER;

        return {
          order: finalOrder,
          column: {
            ...column,
            ...overrideRest,
            ...(userFixed !== undefined ? {fixed: userFixed} : {}),
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
      .sort((left, right) => left.order - right.order)
      .map((item) => item.column);
  }, [properties, colConfigs, visibleKeys, filters, sortField, sortDir, colWidths, handleResize, props.onFilterChange, props.refresh, props.columnOverrides, t, locale])

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
  const dataSource = useMemo(() => normalizeTreeRows(rawDataSource), [rawDataSource]);
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

  const pagination = toPagination(props.pageable);

  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>(props.rowSelection?.selectedKeys ?? []);
  const [selectedRows, setSelectedRows] = useState<T[]>([]);

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
    return currentRows.length === selectedRowKeys.length ? currentRows : selectedRows;
  }, [rowByKey, selectedRowKeys, selectedRows]);

  const onSelectChange = useCallback(
    (keys: React.Key[], rows: T[]) => {
      setSelectedRowKeys(keys);
      setSelectedRows(rows);
      props.onSelectionChange?.(keys, rows);
    },
    [props],
  );

  const onButtonEvent = (button: TableButtonProps): MouseEventHandler<HTMLElement> | undefined => {
    if (button.onClick) return button.onClick as MouseEventHandler<HTMLElement>;
    if (props.onButtonEvents && props.onButtonEvents[button.key]) {
      return () => {
        props.onButtonEvents![button.key](selectedRowKeys, getSelectedRowsForEvent(), button);
      };
    }
    return undefined;
  };

  const onButtonDisabled = (button: TableButtonProps): boolean => {
    const {argumentMinSize, argumentMaxSize} = button;
    if (argumentMinSize === undefined && argumentMaxSize === undefined) return false;
    const size = selectedRowKeys.length;
    if (typeof argumentMinSize === 'number' && argumentMinSize !== -1 && size < argumentMinSize) return true;
    return typeof argumentMaxSize === 'number' && argumentMaxSize !== -1 && size > argumentMaxSize;
  };

  const rowSelection: TableRowSelection<T> = {
    selectedRowKeys,
    onChange: (keys: React.Key[], rows: T[]) => onSelectChange(keys, rows),
  };

  const renderButtons = (buttons?: TableButtonProps[]) => {
    if (!buttons || buttons.length === 0) return null;

    const getButtonText = (button: TableButtonProps) => {
      const anyBtn: any = button as any;
      const raw = anyBtn.text ?? anyBtn.title ?? button.key;
      if (typeof raw === 'string' && raw.startsWith('i18n:')) {
        const key = raw.slice(5);
        return t(key, key);
      }
      return t(`table.button.${button.key}`, raw);
    };

    const getButtonTitleAttr = (button: TableButtonProps): string | undefined => {
      const anyBtn: any = button as any;
      const raw = anyBtn.title as any;
      if (typeof raw === 'string' && raw.startsWith('i18n:')) {
        const key = raw.slice(5);
        return t(key, key);
      }
      return typeof raw === 'string' ? t(`table.button.${button.key}.title`, raw) : undefined;
    };

    return buttons.map((button) => {
      const {argumentMinSize, argumentMaxSize, sort, color, variant, text, icon, title, ...rest} = button as any;
      const mapped: any = {...rest};
      mapped.className = ['sp-table-button', mapped.className].filter(Boolean).join(' ');
      if (color === 'danger') mapped.danger = true;
      if (variant === 'outlined') mapped.ghost = true;
      const iconNode = typeof icon === 'string' ? createIcon(icon) : icon;
      const localizedTitle = getButtonTitleAttr(button);
      if (localizedTitle) mapped.title = localizedTitle;
      return (
        <Button
          {...mapped}
          key={button.key}
          onClick={onButtonEvent(button)}
          disabled={onButtonDisabled(button)}
          icon={iconNode}
        >{getButtonText(button)}</Button>
      );
    });
  };

  const emptyText = useMemo(() => (
    <div className="sp-table-empty">
      <InboxOutlined className="sp-table-empty-icon" />
      <div className="sp-table-empty-title">{t('table.emptyText', '暂无数据')}</div>
    </div>
  ), [t]);

  return (
    <div ref={containerRef} className="sp-table-root" style={{height: '100%', display: 'flex', flexDirection: 'column'}}>
      <div ref={toolbarRef} className="sp-table-toolbar">
        <Row justify="space-between" align="middle" className="sp-table-toolbar-row">
          <Col className="sp-table-toolbar-main">
            <Space className="sp-table-button-group" size={[8, 8]} wrap>
              {renderButtons(props.buttons)}
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
            <Space size={4}>
              <Button
                className="sp-table-icon-button"
                type="text"
                icon={<ReloadOutlined/>}
                onClick={() => props.refresh()}
                loading={props.loading}
                disabled={props.refreshDisabled}
              />
              <Tooltip title={t('table.columnSettings.title', '列设置')}>
                <Button
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
        <AntTable<T>
          className="sp-table-fill"
          bordered
          columns={columns}
          dataSource={dataSource}
          loading={props.loading}
          pagination={pagination}
          rowKey={keyOfRecord}
          onChange={props.onChange}
          rowSelection={rowSelection}
          scroll={{y: scrollY, x: 'max-content'}}
          locale={{emptyText}}
          components={{header: {cell: ResizableTitle}}}
        />
      </div>
      <ColumnSettings
        open={settingsOpen}
        settings={settingsItems}
        onSave={handleSettingsSave}
        onClose={() => setSettingsOpen(false)}
        onReset={handleSettingsReset}
      />
    </div>
  );
};

export default App;
