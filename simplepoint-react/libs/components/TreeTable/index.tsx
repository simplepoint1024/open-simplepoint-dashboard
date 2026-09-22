import {Button, Space, Typography} from 'antd';
import {useEffect, useMemo, useRef, useState} from 'react';
import type {Key} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import Table from '../Table';
import type {TableProps} from '../Table';
import {createChildPages, type ChildPage, type ChildState} from './childPages';

export type TreeTableProps<T extends object> = Omit<TableProps<T>, 'expandable'> & {
  hasChildren: (record: T) => boolean;
  loadChildren?: (record: T, signal?: AbortSignal) => Promise<T[]>;
  loadChildrenPage?: (record: T, page: number, signal: AbortSignal) => Promise<ChildPage<T>>;
};

function rowKey(record: object): string | number | undefined {
  const row = record as {id?: Key; key?: Key};
  const key = row.id ?? row.key;
  return typeof key === 'string' || typeof key === 'number' ? key : undefined;
}

/** Standard table with cancellable, bounded child pages and no synthetic selectable records. */
export default function TreeTable<T extends object>({pageable, hasChildren, loadChildren,
  loadChildrenPage, ...tableProps}: TreeTableProps<T>) {
  const {t} = useI18n();
  const selected = usePageSelected();
  const [states, setStates] = useState(new Map<Key, ChildState<T>>());
  const [expanded, setExpanded] = useState<Key[]>([]);
  const callbacks = useRef({loadChildren, loadChildrenPage});
  callbacks.current = {loadChildren, loadChildrenPage};
  const pager = useMemo(() => createChildPages<T>(async (record, page, signal) => {
    if (callbacks.current.loadChildrenPage) return callbacks.current.loadChildrenPage(record, page, signal);
    return {content: await callbacks.current.loadChildren?.(record, signal) ?? [], hasNext: false};
  }, rowKey, setStates), []);
  useEffect(() => {
    pager.reset(); setExpanded([]);
    return () => pager.reset(false);
  }, [pager, pageable]);
  useEffect(() => {if (!selected) pager.cancelPending();}, [pager, selected]);
  const prepare = (rows: T[]): T[] => rows.map(row => {
    const current = row as T & {children?: T[]};
    const key = rowKey(row);
    const state = key === undefined ? undefined : states.get(key);
    const children = state?.rows ?? current.children;
    return children ? {...current, children: prepare(children)} : hasChildren(row) ? {...current, children: []} : row;
  });
  return <Table<T> {...tableProps} pageable={{...pageable, content: prepare(pageable?.content ?? [])}}
    rowLeadingExtra={record => {
        const key = rowKey(record);
        const state = key === undefined ? undefined : states.get(key);
        if (key === undefined || !expanded.includes(key) || !state || (!state.hasNext && !state.failed)) return null;
        return <Space size={4} wrap>
            {state.failed && <Typography.Text type="danger">{t('table.treeLoadFail', '子节点加载失败，请重试')}</Typography.Text>}
            <Button size="small" type="link" loading={state.loading} disabled={tableProps.loading || !selected}
              onClick={event => {event.stopPropagation(); void pager.next(record);}}
              onDoubleClick={event => event.stopPropagation()}>
              {state.failed ? t('table.retry', '重试') : t('table.loadMore', '加载更多')}
            </Button>
        </Space>;
      }}
    expandable={{childrenColumnName: 'children', expandedRowKeys: expanded,
      rowExpandable: record => hasChildren(record),
      onExpand: (open, record) => {
        const key = rowKey(record);
        if (key === undefined) return;
        setExpanded(previous => open ? [...previous, key] : previous.filter(value => value !== key));
        if (open && selected && !tableProps.loading && !states.has(key)) void pager.next(record);
      }}} />;
}
