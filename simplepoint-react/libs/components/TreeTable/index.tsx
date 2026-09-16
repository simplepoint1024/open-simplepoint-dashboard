import {App} from 'antd';
import {useCallback, useEffect, useRef, useState} from 'react';
import type {Key} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import Table from '../Table';
import type {TableProps} from '../Table';

export type TreeTableProps<T extends object> = Omit<TableProps<T>, 'expandable'> & {
  hasChildren: (record: T) => boolean;
  loadChildren: (record: T) => Promise<T[]>;
};

function rowKey(record: Record<string, unknown>): Key | undefined {
  const key = record.id ?? record.key;
  return typeof key === 'string' || typeof key === 'number' ? key : undefined;
}

function replaceChildren<T extends object>(rows: T[], id: Key, children: T[]): T[] {
  return rows.map(row => {
    const current = row as T & {id?: Key; key?: Key; children?: T[]};
    if ((current.id ?? current.key) === id) return {...current, children};
    if (!Array.isArray(current.children)) return row;
    return {...current, children: replaceChildren(current.children, id, children)};
  });
}

function prepareTreeRows<T extends object>(rows: T[], hasChildren: (record: T) => boolean): T[] {
  return rows.map(row => {
    const current = row as T & {children?: T[]};
    if (Array.isArray(current.children)) {
      return {...current, children: prepareTreeRows(current.children, hasChildren)};
    }
    return hasChildren(row) ? {...current, children: []} : row;
  });
}

/**
 * Platform tree table with lazy direct-child loading.
 *
 * It deliberately builds on the regular platform Table so toolbar actions,
 * column preferences, filters, fullscreen behavior, and visual styling remain
 * identical to flat management pages.
 */
export default function TreeTable<T extends object>({
  pageable,
  hasChildren,
  loadChildren,
  ...tableProps
}: TreeTableProps<T>) {
  const {message} = App.useApp();
  const {t} = useI18n();
  const [content, setContent] = useState<T[]>(() => prepareTreeRows(pageable?.content ?? [], hasChildren));
  const loadingKeys = useRef(new Set<Key>());
  const exhaustedKeys = useRef(new Set<Key>());
  const generation = useRef(0);

  useEffect(() => {
    generation.current += 1;
    loadingKeys.current.clear();
    exhaustedKeys.current.clear();
    setContent(prepareTreeRows(pageable?.content ?? [], hasChildren));
  }, [hasChildren, pageable]);

  const handleExpand = useCallback(async (expanded: boolean, record: T) => {
    const existingChildren = (record as {children?: T[]}).children;
    if (!expanded || (Array.isArray(existingChildren) && existingChildren.length > 0)) return;
    const key = rowKey(record as Record<string, unknown>);
    if (key == null || loadingKeys.current.has(key) || exhaustedKeys.current.has(key)) return;
    const requestGeneration = generation.current;
    loadingKeys.current.add(key);
    try {
      const children = await loadChildren(record);
      if (requestGeneration !== generation.current) return;
      if (children.length === 0) exhaustedKeys.current.add(key);
      setContent(current => replaceChildren(current, key, prepareTreeRows(children, hasChildren)));
    } catch {
      if (requestGeneration === generation.current) {
        message.error(t('table.treeLoadFail', '子节点加载失败，请重试'));
      }
    } finally {
      loadingKeys.current.delete(key);
    }
  }, [hasChildren, loadChildren, message, t]);

  return (
    <Table<T>
      {...tableProps}
      pageable={{...pageable, content}}
      expandable={{
        childrenColumnName: 'children',
        rowExpandable: record => hasChildren(record)
          && !exhaustedKeys.current.has(rowKey(record as Record<string, unknown>) as Key),
        onExpand: (expanded, record) => void handleExpand(expanded, record),
      }}
    />
  );
}
