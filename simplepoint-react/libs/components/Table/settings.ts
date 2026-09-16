export type ColumnFixed = 'left' | 'right' | undefined;

export type ColumnSetting = {
  key: string;
  label: string;
  visible: boolean;
  fixed?: ColumnFixed;
  width?: number;
};

export type TableDisplaySettings = {
  size: 'small' | 'middle' | 'large';
  bordered: boolean;
  striped: boolean;
  wrapText: boolean;
  stickyHeader: boolean;
  rowHover: boolean;
  showSizeChanger: boolean;
  showQuickJumper: boolean;
};

export const MIN_TABLE_COLUMN_WIDTH = 80;
export const MAX_TABLE_COLUMN_WIDTH = 1200;
export const DEFAULT_FIXED_COLUMN_WIDTH = 160;

export const DEFAULT_TABLE_DISPLAY_SETTINGS: TableDisplaySettings = {
  size: 'middle',
  bordered: true,
  striped: true,
  wrapText: false,
  stickyHeader: true,
  rowHover: true,
  showSizeChanger: true,
  showQuickJumper: true,
};

/** Ant Design requires fixed-left and fixed-right columns to stay at their edges. */
export const normalizeColumnSettings = (items: ColumnSetting[]): ColumnSetting[] => [
  ...items.filter(item => item.fixed === 'left'),
  ...items.filter(item => !item.fixed),
  ...items.filter(item => item.fixed === 'right'),
];

export const normalizeTableDisplaySettings = (
  value: Partial<TableDisplaySettings> | null | undefined,
  defaults: TableDisplaySettings = DEFAULT_TABLE_DISPLAY_SETTINGS,
): TableDisplaySettings => {
  const size = value?.size === 'small' || value?.size === 'middle' || value?.size === 'large'
    ? value.size
    : defaults.size;
  const booleanValue = <K extends keyof TableDisplaySettings>(key: K): boolean => (
    typeof value?.[key] === 'boolean' ? value[key] as boolean : defaults[key] as boolean
  );
  return {
    size,
    bordered: booleanValue('bordered'),
    striped: booleanValue('striped'),
    wrapText: booleanValue('wrapText'),
    stickyHeader: booleanValue('stickyHeader'),
    rowHover: booleanValue('rowHover'),
    showSizeChanger: booleanValue('showSizeChanger'),
    showQuickJumper: booleanValue('showQuickJumper'),
  };
};

const normalizeWidth = (value: unknown, fallback: number | undefined): number | undefined => {
  if (typeof value !== 'number' || !Number.isFinite(value)) return fallback;
  return Math.max(MIN_TABLE_COLUMN_WIDTH, Math.min(MAX_TABLE_COLUMN_WIDTH, Math.round(value)));
};

/** Merge stored preferences with the latest column definition without reviving removed columns. */
export const mergeColumnSettings = (
  defaults: ColumnSetting[],
  saved: ColumnSetting[] | undefined,
): ColumnSetting[] => {
  const savedByKey = new Map((saved ?? []).map(item => [item.key, item]));
  const savedOrder = new Map((saved ?? []).map((item, index) => [item.key, index]));
  const merged = defaults.map((item, defaultIndex) => {
    const stored = savedByKey.get(item.key);
    const hasStoredFixed = Boolean(stored && Object.prototype.hasOwnProperty.call(stored, 'fixed'));
    const fixed = hasStoredFixed && (stored?.fixed === 'left' || stored?.fixed === 'right')
      ? stored.fixed
      : hasStoredFixed ? undefined : item.fixed;
    const next: ColumnSetting & {_order: number} = {
      key: item.key,
      label: item.label,
      visible: typeof stored?.visible === 'boolean' ? stored.visible : item.visible,
      _order: savedOrder.get(item.key) ?? (savedOrder.size + defaultIndex),
    };
    const width = normalizeWidth(stored?.width, item.width);
    if (fixed) next.fixed = fixed;
    if (width !== undefined) next.width = width;
    return next;
  });
  merged.sort((left, right) => left._order - right._order);
  return normalizeColumnSettings(merged.map(({_order: _, ...item}) => item));
};
