import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import type {Key} from 'react';
import type {TableColumnsType, TableProps, TransferProps} from 'antd';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import STableTransfer, {type STableTransferProps} from '../STableTransfer';

const DEFAULT_PAGE_SIZE = 10;
const DEFAULT_SELECTED_LOOKUP_PAGE_SIZE = 1000;
const EMPTY_DEPS: readonly unknown[] = [];
const COMMON_CONTEXT_PATH = '/common';

export type EntityTransferDirection = 'left' | 'right';
export type EntityTransferKey = string | number;

export interface EntityTransferQuery {
  page: number;
  pageSize: number;
  search: string;
}

export type EntityTransferFetchPage<T> = (query: EntityTransferQuery) => Promise<Page<T>>;
export type EntityTransferFetchKeys = () => Promise<EntityTransferKey[]>;
export type EntityTransferFetchSelected<T> = (keys: string[]) => Promise<T[]>;
export type EntityTransferMoveHandler = (keys: string[], nextKeys: string[]) => Promise<unknown> | unknown;

export interface EntityTransferProps<T extends object>
  extends Omit<
    STableTransferProps<T>,
    | 'dataSource'
    | 'defaultValue'
    | 'targetKeys'
    | 'onChange'
    | 'leftColumns'
    | 'rightColumns'
    | 'itemKey'
    | 'leftPagination'
    | 'rightPagination'
    | 'onPanelSearch'
    | 'loading'
  > {
  columns: TableColumnsType<T>;
  leftColumns?: TableColumnsType<T>;
  rightColumns?: TableColumnsType<T>;
  itemKey?: keyof T | string;
  fetchItems: EntityTransferFetchPage<T>;
  fetchAuthorizedKeys?: EntityTransferFetchKeys;
  fetchSelectedItems?: EntityTransferFetchSelected<T>;
  selectedLookupPageSize?: number;
  value?: EntityTransferKey[];
  defaultValue?: EntityTransferKey[];
  enabled?: boolean;
  defaultPageSize?: number;
  queryDeps?: readonly unknown[];
  disabledKeys?: EntityTransferKey[];
  getItemDisabled?: (item: T) => boolean;
  onValueChange?: (nextKeys: string[], direction: EntityTransferDirection, moveKeys: string[]) => void;
  onAuthorize?: EntityTransferMoveHandler;
  onUnauthorize?: EntityTransferMoveHandler;
  onMoveError?: (error: unknown) => void;
  onLoadError?: (error: unknown) => void;
}

export interface UserTransferItem {
  id: string;
  name?: string;
  nickname?: string;
  username?: string;
  email?: string;
  phoneNumber?: string;
  disabled?: boolean;
}

export interface RoleTransferItem {
  id: string;
  name?: string;
  roleName?: string;
  authority?: string;
  description?: string;
  disabled?: boolean;
}

export interface OrganizationTransferItem {
  id: string;
  name: string;
  code?: string;
  type?: string;
  parentId?: string | null;
  description?: string | null;
  disabled?: boolean;
}

type BusinessTransferProps<T extends object> = Omit<
  EntityTransferProps<T>,
  'columns' | 'fetchItems' | 'itemKey'
> & {
  columns?: TableColumnsType<T>;
  fetchItems?: EntityTransferFetchPage<T>;
  itemKey?: keyof T | string;
  endpoint?: string;
  searchParamName?: string;
};

function toKeyString(value: unknown): string {
  if (value == null) return '';
  return String(value);
}

function normalizeKeys(keys?: readonly EntityTransferKey[] | readonly Key[] | null): string[] {
  return (keys ?? []).map(toKeyString).filter((key) => key.length > 0);
}

function readItemKey<T extends object>(item: T, itemKey: keyof T | string): string {
  const record = item as Record<string, unknown>;
  return toKeyString(record[itemKey as string] ?? record.id ?? record.key);
}

function orderItemsByKeys<T extends object>(
  keys: string[],
  items: T[],
  itemKey: keyof T | string,
): T[] {
  const itemMap = new Map<string, T>();
  items.forEach((item) => {
    const key = readItemKey(item, itemKey);
    if (key) itemMap.set(key, item);
  });
  return keys.map((key) => itemMap.get(key)).filter((item): item is T => !!item);
}

function buildPageParams(
  query: EntityTransferQuery,
  searchParamName: string,
): Record<string, string> {
  const params: Record<string, string> = {
    page: String(query.page),
    size: String(query.pageSize),
  };
  const search = query.search.trim();
  if (search) {
    params[searchParamName] = search;
  }
  return params;
}

async function fetchPage<T>(
  endpoint: string,
  query: EntityTransferQuery,
  searchParamName: string,
): Promise<Page<T>> {
  return await get<Page<T>>(endpoint, buildPageParams(query, searchParamName));
}

function queryDepsSignature(queryDeps: readonly unknown[]) {
  return queryDeps.map((dep) => String(dep ?? '')).join('\u0001');
}

const EntityTransfer = <T extends object>({
  columns,
  leftColumns,
  rightColumns,
  itemKey = 'id',
  fetchItems,
  fetchAuthorizedKeys,
  fetchSelectedItems,
  selectedLookupPageSize = DEFAULT_SELECTED_LOOKUP_PAGE_SIZE,
  value,
  defaultValue,
  enabled = true,
  disabled,
  defaultPageSize = DEFAULT_PAGE_SIZE,
  queryDeps = EMPTY_DEPS,
  disabledKeys,
  getItemDisabled,
  onValueChange,
  onAuthorize,
  onUnauthorize,
  onMoveError,
  onLoadError,
  searchable = true,
  showSelectAll = false,
  ...transferProps
}: EntityTransferProps<T>) => {
  const isControlled = value !== undefined;
  const [internalTargetKeys, setInternalTargetKeys] = useState<string[]>(() => normalizeKeys(defaultValue));
  const targetKeys = useMemo(
    () => normalizeKeys(isControlled ? value : internalTargetKeys),
    [internalTargetKeys, isControlled, value],
  );
  const targetKeySignature = targetKeys.join('\u0001');

  const [leftPage, setLeftPage] = useState({current: 1, pageSize: defaultPageSize});
  const [rightPage, setRightPage] = useState({current: 1, pageSize: defaultPageSize});
  const [searchValues, setSearchValues] = useState({left: '', right: ''});
  const [availableItems, setAvailableItems] = useState<T[]>([]);
  const [availableTotal, setAvailableTotal] = useState(0);
  const [selectedItems, setSelectedItems] = useState<T[]>([]);
  const [availableLoading, setAvailableLoading] = useState(false);
  const [authorizedLoading, setAuthorizedLoading] = useState(false);
  const [selectedLoading, setSelectedLoading] = useState(false);
  const [moving, setMoving] = useState(false);

  const availableItemsRef = useRef<T[]>([]);
  const selectedItemsRef = useRef<T[]>([]);
  const querySignature = useMemo(() => queryDepsSignature(queryDeps), [queryDeps]);
  const mountedRef = useRef(false);

  useEffect(() => {
    availableItemsRef.current = availableItems;
  }, [availableItems]);

  useEffect(() => {
    selectedItemsRef.current = selectedItems;
  }, [selectedItems]);

  useEffect(() => {
    if (!mountedRef.current) {
      mountedRef.current = true;
      return;
    }
    setLeftPage((prev) => ({...prev, current: 1}));
    setRightPage((prev) => ({...prev, current: 1}));
    setSearchValues({left: '', right: ''});
    setSelectedItems([]);
    if (!isControlled) {
      setInternalTargetKeys([]);
    }
  }, [isControlled, querySignature]);

  useEffect(() => {
    let cancelled = false;
    if (!enabled) {
      setAvailableItems([]);
      setAvailableTotal(0);
      return;
    }

    setAvailableLoading(true);
    void fetchItems({
      page: leftPage.current - 1,
      pageSize: leftPage.pageSize,
      search: searchValues.left,
    })
      .then((page) => {
        if (cancelled) return;
        setAvailableItems(page?.content ?? []);
        setAvailableTotal(page?.page?.totalElements ?? page?.content?.length ?? 0);
      })
      .catch((error) => {
        if (cancelled) return;
        setAvailableItems([]);
        setAvailableTotal(0);
        onLoadError?.(error);
      })
      .finally(() => {
        if (!cancelled) setAvailableLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [
    enabled,
    fetchItems,
    leftPage.current,
    leftPage.pageSize,
    onLoadError,
    searchValues.left,
    ...queryDeps,
  ]);

  useEffect(() => {
    let cancelled = false;
    if (!enabled || !fetchAuthorizedKeys) {
      setAuthorizedLoading(false);
      return;
    }

    setAuthorizedLoading(true);
    void fetchAuthorizedKeys()
      .then((keys) => {
        if (cancelled || isControlled) return;
        setInternalTargetKeys(normalizeKeys(keys));
      })
      .catch((error) => {
        if (!cancelled) onLoadError?.(error);
      })
      .finally(() => {
        if (!cancelled) setAuthorizedLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [enabled, fetchAuthorizedKeys, isControlled, onLoadError, ...queryDeps]);

  useEffect(() => {
    let cancelled = false;
    if (!enabled || targetKeys.length === 0) {
      setSelectedItems([]);
      setSelectedLoading(false);
      return;
    }

    const localItems = [...selectedItemsRef.current, ...availableItemsRef.current];
    const localOrderedItems = orderItemsByKeys(targetKeys, localItems, itemKey);
    if (!fetchSelectedItems && localOrderedItems.length === targetKeys.length) {
      setSelectedItems(localOrderedItems);
      return;
    }

    setSelectedLoading(true);
    const loadSelectedItems = async () => {
      if (fetchSelectedItems) {
        return await fetchSelectedItems(targetKeys);
      }
      if (selectedLookupPageSize <= 0) {
        return localOrderedItems;
      }
      const page = await fetchItems({
        page: 0,
        pageSize: selectedLookupPageSize,
        search: '',
      });
      const selectedKeySet = new Set(targetKeys);
      return (page?.content ?? []).filter((item) => selectedKeySet.has(readItemKey(item, itemKey)));
    };

    void loadSelectedItems()
      .then((items) => {
        if (cancelled) return;
        const mergedItems = [...selectedItemsRef.current, ...availableItemsRef.current, ...items];
        setSelectedItems(orderItemsByKeys(targetKeys, mergedItems, itemKey));
      })
      .catch((error) => {
        if (!cancelled) onLoadError?.(error);
      })
      .finally(() => {
        if (!cancelled) setSelectedLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [
    enabled,
    fetchItems,
    fetchSelectedItems,
    itemKey,
    onLoadError,
    selectedLookupPageSize,
    targetKeySignature,
    ...queryDeps,
  ]);

  const dataSource = useMemo(() => {
    const disabledKeySet = new Set(normalizeKeys(disabledKeys));
    const itemMap = new Map<string, T>();
    [...availableItems, ...selectedItems].forEach((item) => {
      const key = readItemKey(item, itemKey);
      if (!key) return;
      const itemDisabled = disabledKeySet.has(key) || getItemDisabled?.(item);
      itemMap.set(key, {
        ...item,
        disabled: Boolean((item as Record<string, unknown>).disabled || itemDisabled),
      } as T);
    });
    return Array.from(itemMap.values());
  }, [availableItems, disabledKeys, getItemDisabled, itemKey, selectedItems]);

  const leftPagination: TableProps<T>['pagination'] = {
    current: leftPage.current,
    pageSize: leftPage.pageSize,
    total: availableTotal,
    showSizeChanger: true,
    showQuickJumper: true,
    onChange: (current, pageSize) => setLeftPage({current, pageSize}),
  };

  const rightPagination: TableProps<T>['pagination'] = {
    current: rightPage.current,
    pageSize: rightPage.pageSize,
    showSizeChanger: true,
    showQuickJumper: true,
    onChange: (current, pageSize) => setRightPage({current, pageSize}),
  };

  const handlePanelSearch = (direction: EntityTransferDirection, search: string) => {
    setSearchValues((prev) => ({...prev, [direction]: search}));
    if (direction === 'left') {
      setLeftPage((prev) => ({...prev, current: 1}));
    } else {
      setRightPage((prev) => ({...prev, current: 1}));
    }
  };

  const handleChange: TransferProps['onChange'] = (nextTargetKeys, direction, moveKeys) => {
    const previousKeys = targetKeys;
    const previousSelectedItems = selectedItemsRef.current;
    const nextKeys = normalizeKeys(nextTargetKeys);
    const movedKeys = normalizeKeys(moveKeys);
    const transferDirection = direction as EntityTransferDirection;

    if (!isControlled) {
      setInternalTargetKeys(nextKeys);
    }
    setRightPage((prev) => ({...prev, current: 1}));

    const itemMap = new Map<string, T>();
    [...selectedItemsRef.current, ...availableItemsRef.current, ...dataSource].forEach((item) => {
      const key = readItemKey(item, itemKey);
      if (key) itemMap.set(key, item);
    });
    setSelectedItems(nextKeys.map((key) => itemMap.get(key)).filter((item): item is T => !!item));
    onValueChange?.(nextKeys, transferDirection, movedKeys);

    void (async () => {
      try {
        setMoving(true);
        if (transferDirection === 'right') {
          await onAuthorize?.(movedKeys, nextKeys);
        } else {
          await onUnauthorize?.(movedKeys, nextKeys);
        }
      } catch (error) {
        if (!isControlled) {
          setInternalTargetKeys(previousKeys);
        }
        setSelectedItems(previousSelectedItems);
        onMoveError?.(error);
      } finally {
        setMoving(false);
      }
    })();
  };

  return (
    <STableTransfer
      {...transferProps}
      dataSource={dataSource}
      targetKeys={targetKeys}
      showSelectAll={showSelectAll}
      disabled={disabled || !enabled || moving}
      loading={availableLoading || authorizedLoading || selectedLoading || moving}
      onChange={handleChange}
      leftColumns={leftColumns ?? columns}
      rightColumns={rightColumns ?? columns}
      itemKey={itemKey}
      adaptiveHeight={transferProps.adaptiveHeight}
      searchable={searchable}
      leftPagination={leftPagination}
      rightPagination={rightPagination}
      onPanelSearch={handlePanelSearch}
    />
  );
};

export function UserTransferSelect<T extends UserTransferItem = UserTransferItem>({
  columns,
  fetchItems,
  itemKey = 'id',
  endpoint = `${COMMON_CONTEXT_PATH}/users`,
  searchParamName = 'keyword',
  ...props
}: BusinessTransferProps<T>) {
  const {t, messages} = useI18n();
  const defaultColumns = useMemo<TableColumnsType<T>>(
    () => [
      {
        key: 'name',
        title: t('users.title.nickname', '用户'),
        render: (_value, record) =>
          record.name || record.nickname || record.username || record.id,
      },
      {key: 'email', dataIndex: 'email', title: t('users.title.email', '邮箱')},
      {key: 'phoneNumber', dataIndex: 'phoneNumber', title: t('users.title.phoneNumber', '手机号')},
    ],
    [messages, t],
  );
  const defaultFetchItems = useCallback(
    (query: EntityTransferQuery) => fetchPage<T>(endpoint, query, searchParamName),
    [endpoint, searchParamName],
  );

  return (
    <EntityTransfer
      {...props}
      columns={columns ?? defaultColumns}
      fetchItems={fetchItems ?? defaultFetchItems}
      itemKey={itemKey}
    />
  );
}

export function RoleTransferSelect<T extends RoleTransferItem = RoleTransferItem>({
  columns,
  fetchItems,
  itemKey = 'id',
  endpoint = `${COMMON_CONTEXT_PATH}/roles/items`,
  searchParamName = 'keyword',
  ...props
}: BusinessTransferProps<T>) {
  const {t, messages} = useI18n();
  const defaultColumns = useMemo<TableColumnsType<T>>(
    () => [
      {
        key: 'name',
        title: t('roles.title.roleName', '角色'),
        render: (_value, record) => record.name || record.roleName || record.authority || record.id,
      },
      {key: 'description', dataIndex: 'description', title: t('roles.title.description', '描述')},
    ],
    [messages, t],
  );
  const defaultFetchItems = useCallback(
    (query: EntityTransferQuery) => fetchPage<T>(endpoint, query, searchParamName),
    [endpoint, searchParamName],
  );

  return (
    <EntityTransfer
      {...props}
      columns={columns ?? defaultColumns}
      fetchItems={fetchItems ?? defaultFetchItems}
      itemKey={itemKey}
    />
  );
}

export function OrganizationTransferSelect<T extends OrganizationTransferItem = OrganizationTransferItem>({
  columns,
  fetchItems,
  itemKey = 'id',
  endpoint = `${COMMON_CONTEXT_PATH}/platform/organizations`,
  searchParamName = 'keyword',
  ...props
}: BusinessTransferProps<T>) {
  const {t, messages} = useI18n();
  const defaultColumns = useMemo<TableColumnsType<T>>(
    () => [
      {key: 'name', dataIndex: 'name', title: t('organizations.title.name', '组织名称')},
      {key: 'code', dataIndex: 'code', title: t('organizations.title.code', '组织编码')},
      {key: 'type', dataIndex: 'type', title: t('organizations.title.type', '组织类型')},
    ],
    [messages, t],
  );
  const defaultFetchItems = useCallback(
    (query: EntityTransferQuery) => fetchPage<T>(endpoint, query, searchParamName),
    [endpoint, searchParamName],
  );

  return (
    <EntityTransfer
      {...props}
      columns={columns ?? defaultColumns}
      fetchItems={fetchItems ?? defaultFetchItems}
      itemKey={itemKey}
    />
  );
}

export default EntityTransfer;
