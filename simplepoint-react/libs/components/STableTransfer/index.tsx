import type {GetProp, TableColumnsType, TableProps, TransferProps} from 'antd';
import {Table, Transfer} from "antd";
import Highlighter from 'react-highlight-words';
import {useEffect, useRef, useState} from 'react';
import type {CSSProperties} from 'react';

type TransferItem = GetProp<TransferProps, 'dataSource'>[number];
type TableRowSelection<T extends object> = TableProps<T>['rowSelection'];
type TransferSearchDirection = Parameters<NonNullable<TransferProps<TransferItem>['onSearch']>>[0];

export interface STableTransferProps<T> extends TransferProps<TransferItem> {
  dataSource: T[];
  leftColumns: TableColumnsType<T>;
  rightColumns: TableColumnsType<T>;
  // 不使用 React 的保留 prop 名称 `key`，改为 itemKey
  itemKey?: keyof T | string;
  /** 左右两侧统一高度（像素），用于表格内部滚动。默认 400 */
  listHeight?: number;
  /** 左侧表格纵向滚动高度优先级高于 listHeight */
  leftScrollY?: number;
  /** 右侧表格纵向滚动高度优先级高于 listHeight */
  rightScrollY?: number;
  /** 自适应父容器高度，动态计算滚动高度（优先级最高）。父容器需有确定高度（flex/百分比/固定值） */
  adaptiveHeight?: boolean;
  /** 当使用 adaptiveHeight 时，从实测高度中额外再减去的偏移量（已经自动减去 header/search/footer）。默认 0 */
  scrollOffset?: number;
  /** 是否显示搜索框（显示在左右列表头部，使用 Transfer 内置搜索） */
  searchable?: boolean;
  /** 是否对匹配文本进行高亮 */
  highlight?: boolean;
  /** 高亮样式自定义 */
  highlightStyle?: CSSProperties;
  /** 左侧分页配置 */
  leftPagination?: TableProps<T>['pagination'];
  /** 右侧分页配置 */
  rightPagination?: TableProps<T>['pagination'];
  /** 面板搜索事件。上层需要远程搜索时可监听该事件。 */
  onPanelSearch?: (direction: TransferSearchDirection, value: string) => void;
  /** 是否允许双击行直接左右穿梭。默认开启 */
  doubleClickToMove?: boolean;
  /** 是否允许单击行切换勾选。默认开启 */
  rowClickToSelect?: boolean;
  /** 表格加载状态 */
  loading?: TableProps<T>['loading'];
}

const App = <T,>({
  leftColumns,
  rightColumns,
  itemKey = 'id',
  listHeight = 400,
  leftScrollY,
  rightScrollY,
  adaptiveHeight = false,
  scrollOffset = 0,
  searchable = false,
  highlight = true,
  highlightStyle,
  leftPagination = {pageSize: 10, showSizeChanger: true, showQuickJumper: true},
  rightPagination = {pageSize: 10, showSizeChanger: true, showQuickJumper: true},
  onPanelSearch,
  doubleClickToMove = true,
  rowClickToSelect = true,
  loading,
  ...restProps
}: STableTransferProps<T>) => {
  // 统一行主键：优先使用 item[itemKey]，其次使用 id、key，最终保证返回字符串（避免返回 'undefined'）
  const getRowKey = (item: any) => {
    const val = item?.[itemKey as string] ?? item?.id ?? item?.key;
    return String(val ?? '');
  };

  const targetKeys = (restProps.targetKeys as (string | number)[] | undefined) || [];
  const onChange = restProps.onChange;

  const [leftAutoHeight, setLeftAutoHeight] = useState<number | undefined>();
  const [rightAutoHeight, setRightAutoHeight] = useState<number | undefined>();
  const leftPanelRef = useRef<HTMLDivElement | null>(null);
  const rightPanelRef = useRef<HTMLDivElement | null>(null);
  const [searchValues, setSearchValues] = useState<{left: string; right: string}>({left: '', right: ''});

  // 提取所有叶子列的 dataIndex（支持分组列）
  const getLeafDataIndexes = (cols: TableColumnsType<any>): Array<string | number> => {
    const result: Array<string | number> = [];
    const walk = (arr: any[]) => {
      arr?.forEach((col) => {
        if (col && Array.isArray(col.children) && col.children.length) {
          walk(col.children);
        } else if (col && col.dataIndex != null) {
          result.push(col.dataIndex as any);
        }
      });
    };
    walk(cols as any);
    return result;
  };

  // 递归包装列渲染以实现高亮（仅作用于叶子列）
  const decorateColumnsForHighlight = (cols: TableColumnsType<any>, searchValue: string): TableColumnsType<any> => {
    return (cols as any).map((col: any) => {
      if (Array.isArray(col?.children) && col.children.length) {
        return {
          ...col,
          children: decorateColumnsForHighlight(col.children, searchValue),
        };
      }
      if (col?.dataIndex == null) return col;
      const dataIndex = col.dataIndex;
      const originalRender = col.render;
      return {
        ...col,
        render: (text: any, record: any, index: number) => {
          const raw = originalRender ? originalRender(text, record, index) : (text ?? record?.[dataIndex]);
          if (!searchValue) return raw;
          const str = (typeof raw === 'string' || typeof raw === 'number') ? String(raw) : '';
          if (!str) return raw;
          return (
            <Highlighter
              searchWords={[searchValue]}
              autoEscape
              textToHighlight={str}
              highlightStyle={highlightStyle || {backgroundColor: '#ffc069', padding: 0}}
            />
          );
        }
      };
    });
  };

  // 监听父容器高度变化
  useEffect(() => {
    if (!adaptiveHeight) return;

    const computeScroll = (panel: HTMLDivElement | null): number | undefined => {
      if (!panel) return undefined;
      // 查找 Transfer 面板根节点 (.ant-transfer-list)
      const listRoot = panel.closest('.ant-transfer-list') as HTMLElement | null;
      const full = listRoot?.clientHeight ?? panel.clientHeight;
      // 查找需要减去的结构
      const header = listRoot?.querySelector('.ant-transfer-list-header') as HTMLElement | null;
      const search = listRoot?.querySelector('.ant-transfer-list-body-search-wrapper') as HTMLElement | null;
      const footer = listRoot?.querySelector('.ant-transfer-list-footer') as HTMLElement | null;
      const subtract = (header?.offsetHeight || 0) + (search?.offsetHeight || 0) + (footer?.offsetHeight || 0);
      const body = full - subtract - scrollOffset; // 额外偏移
      return Math.max(50, body);
    };

    const updateLeft = () => setLeftAutoHeight(computeScroll(leftPanelRef.current));
    const updateRight = () => setRightAutoHeight(computeScroll(rightPanelRef.current));

    // 初始计算
    updateLeft();
    updateRight();

    const observers: ResizeObserver[] = [];
    const observe = (panel: HTMLDivElement | null, cb: () => void) => {
      if (!panel) return;
      const listRoot = panel.closest('.ant-transfer-list') as HTMLElement | null;
      const target = listRoot || panel;
      const ro = new ResizeObserver(() => cb());
      ro.observe(target);
      observers.push(ro);
    };

    observe(leftPanelRef.current, updateLeft);
    observe(rightPanelRef.current, updateRight);

    window.addEventListener('resize', updateLeft);
    window.addEventListener('resize', updateRight);

    return () => {
      observers.forEach(o => o.disconnect());
      window.removeEventListener('resize', updateLeft);
      window.removeEventListener('resize', updateRight);
    };
  }, [adaptiveHeight, scrollOffset]);

  // 默认跨全部列的过滤（仅在用户未提供 filterOption 且 searchable 时启用）
  const defaultFilterOption = searchable && !restProps.filterOption
    ? ((input: string, item: any) => {
        const lower = input.toLowerCase();
        const indexes = Array.from(new Set([
          ...getLeafDataIndexes(leftColumns as any),
          ...getLeafDataIndexes(rightColumns as any),
        ]));
        return indexes.some((key) => {
          const value = item?.[key as any];
          return value != null && String(value).toLowerCase().includes(lower);
        });
      })
    : undefined;

  return (
    <Transfer
      {...restProps}
      rowKey={getRowKey}
      style={{...(restProps.style || {}), width: '100%', height: adaptiveHeight ? '100%' : (restProps.style?.height as any)}}
      listStyle={adaptiveHeight ? {height: '100%'} : (restProps as any).listStyle}
      showSearch={searchable}
      filterOption={restProps.filterOption ?? defaultFilterOption}
      onSearch={(direction, value) => {
        setSearchValues(prev => ({...prev, [direction]: value}));
        onPanelSearch?.(direction, value);
        restProps.onSearch?.(direction, value);
      }}
    >
      {({
          direction,
          filteredItems,
          onItemSelect,
          onItemSelectAll,
          selectedKeys: listSelectedKeys,
          disabled: listDisabled,
        }) => {
        const columns = direction === 'left' ? leftColumns : rightColumns;
        const searchValue = direction === 'left' ? searchValues.left : searchValues.right;
        const decoratedColumns: TableColumnsType<any> = (searchable && highlight && searchValue)
          ? decorateColumnsForHighlight(columns as any, searchValue)
          : columns;

        const rowSelection: TableRowSelection<TransferItem> = {
          getCheckboxProps: () => ({disabled: listDisabled}),
          onChange(selectedRowKeys) {
            onItemSelectAll(selectedRowKeys, 'replace');
          },
          selectedRowKeys: listSelectedKeys,
          selections: [Table.SELECTION_ALL, Table.SELECTION_INVERT, Table.SELECTION_NONE],
        };

        // 计算滚动高度：优先使用自适应，其次左右独立设置，最后统一高度
        const baseConfigured = direction === 'left' ? (leftScrollY ?? listHeight) : (rightScrollY ?? listHeight);
        const scrollY = adaptiveHeight
          ? (direction === 'left' ? leftAutoHeight : rightAutoHeight) ?? baseConfigured
          : baseConfigured;

        const panelRef = direction === 'left' ? leftPanelRef : rightPanelRef;
        const pagination = direction === 'left' ? leftPagination : rightPagination;
        const paginationEnabled = pagination !== false && pagination != null;
        const tableScrollY = paginationEnabled ? Math.max(120, scrollY - 56) : scrollY;

        return (
          <div ref={panelRef} style={{height: adaptiveHeight ? '100%' : scrollY, overflow: 'hidden', display: 'flex', flexDirection: 'column'}}>
            <Table
               // 确保 Table 与 Transfer 使用相同的 rowKey
               pagination={pagination}
               rowKey={getRowKey}
               rowSelection={rowSelection}
               columns={decoratedColumns as any}
               dataSource={filteredItems as any}
               loading={loading}
               style={{pointerEvents: listDisabled ? 'none' : undefined}}
               // 独立滚动，不影响外层主布局
               scroll={{y: tableScrollY}}
               onRow={(record: any) => ({
                 onClick: () => {
                   // 单击切换勾选（不直接移动）
                   if (!rowClickToSelect) return;
                   const key = getRowKey(record);
                   const itemDisabled = record?.disabled;
                   if (itemDisabled || listDisabled) return;
                   onItemSelect(key, !listSelectedKeys.includes(key));
                 },
                 // Ant Design Table 会读取 onDoubleClick 事件，此处用于穿梭双击移动。
                 // eslint-disable-next-line @typescript-eslint/no-unused-vars
                 onDoubleClick: () => {
                   if (!doubleClickToMove) return;
                   const key = getRowKey(record);
                   const itemDisabled = record?.disabled;
                   if (itemDisabled || listDisabled) return;
                   if (!onChange) return;
                   const keyStr = String(key);
                   const current = targetKeys.map(k => String(k));
                   if (direction === 'left') {
                     if (!current.includes(keyStr)) {
                       onChange([...targetKeys, keyStr], 'right', [keyStr]);
                     }
                   } else {
                     if (current.includes(keyStr)) {
                       onChange(targetKeys.filter(k => String(k) !== keyStr), 'left', [keyStr]);
                     }
                   }
                 },
               })}
             />
          </div>
        );
      }}
    </Transfer>
  );
};

export default App;
