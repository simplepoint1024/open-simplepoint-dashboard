import {TreeSelect, Spin, Typography} from 'antd';
import type {TreeSelectProps} from 'antd';
import {useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore} from 'react';
import {get} from '@simplepoint/shared/api/methods';
import {
  EMPTY_QUERY_SCOPE,
  getQueryScopeSnapshot,
  subscribeQueryScope,
} from '@simplepoint/shared/api/queryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

export interface OrganizationOption {
  id: string;
  name: string;
  code?: string | null;
  parentId?: string | null;
  type?: string | null;
  description?: string | null;
  sort?: number | null;
  enabled: boolean;
  hasChildren: boolean;
}

type OrganizationOptionPage = {
  content: OrganizationOption[];
  number: number;
  size: number;
  hasNext: boolean;
};

type OrganizationNode = {
  key: string;
  value: string;
  title: string;
  isLeaf: boolean;
  disabled?: boolean;
  children?: OrganizationNode[];
};

export type OrganizationSelectProps = Omit<
  TreeSelectProps,
  'treeData' | 'loadData' | 'labelInValue' | 'value' | 'onChange' | 'onSearch'
> & {
  value?: string | null;
  onChange?: (value?: string) => void;
  endpoint?: string;
  excludeId?: string;
  pageSize?: number;
  debounceMs?: number;
  onlyEnabled?: boolean;
};

const DEFAULT_ENDPOINT = '/common/platform/organizations/options';
const DEFAULT_PAGE_SIZE = 30;
const DEFAULT_DEBOUNCE_MS = 350;

function labelOf(option: OrganizationOption) {
  return option.code ? `${option.name} (${option.code})` : option.name;
}

function toNode(option: OrganizationOption, onlyEnabled: boolean): OrganizationNode {
  return {
    key: option.id,
    value: option.id,
    title: labelOf(option),
    isLeaf: !option.hasChildren,
    disabled: onlyEnabled && !option.enabled,
  };
}

function mergeNodes(current: OrganizationNode[], incoming: OrganizationNode[]) {
  const nodes = new Map(current.map(node => [node.value, node]));
  incoming.forEach(node => nodes.set(node.value, {...nodes.get(node.value), ...node}));
  return [...nodes.values()];
}

function setChildren(
  nodes: OrganizationNode[],
  parentId: string,
  children: OrganizationNode[],
): OrganizationNode[] {
  return nodes.map(node => {
    if (node.value === parentId) return {...node, children};
    if (!node.children) return node;
    return {...node, children: setChildren(node.children, parentId, children)};
  });
}

function positiveInteger(value: number | undefined, fallback: number) {
  return Number.isInteger(value) && Number(value) > 0 ? Number(value) : fallback;
}

/**
 * Shared tenant organization picker.
 *
 * Roots and direct children are loaded lazily. Keyword searches are served by
 * the backend in count-free pages, so the component stays bounded for very
 * large organization trees.
 */
export default function OrganizationSelect({
  value,
  onChange,
  endpoint = DEFAULT_ENDPOINT,
  excludeId,
  pageSize: requestedPageSize,
  debounceMs: requestedDebounceMs,
  onlyEnabled = false,
  allowClear = true,
  placeholder,
  disabled,
  ...rest
}: OrganizationSelectProps) {
  const {t} = useI18n();
  const scope = useSyncExternalStore(
    subscribeQueryScope,
    getQueryScopeSnapshot,
    () => EMPTY_QUERY_SCOPE,
  );
  const pageSize = positiveInteger(requestedPageSize, DEFAULT_PAGE_SIZE);
  const debounceMs = positiveInteger(requestedDebounceMs, DEFAULT_DEBOUNCE_MS);
  const [treeData, setTreeData] = useState<OrganizationNode[]>([]);
  const [searchData, setSearchData] = useState<OrganizationNode[]>([]);
  const [selected, setSelected] = useState<OrganizationOption>();
  const [searchValue, setSearchValue] = useState('');
  const [rootPage, setRootPage] = useState(-1);
  const [rootHasNext, setRootHasNext] = useState(false);
  const [searchPage, setSearchPage] = useState(-1);
  const [searchHasNext, setSearchHasNext] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const requestSequence = useRef(0);
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const requestOptions = useCallback(async (params: Record<string, string>) => {
    return get<OrganizationOptionPage>(endpoint, {
      size: String(pageSize),
      ...(excludeId ? {excludeId} : {}),
      ...params,
    });
  }, [endpoint, excludeId, pageSize]);

  const loadRoots = useCallback(async (page = 0, append = false) => {
    const sequence = ++requestSequence.current;
    setLoading(true);
    setLoadError(false);
    try {
      const result = await requestOptions({page: String(page)});
      if (sequence !== requestSequence.current) return;
      const nodes = (result.content ?? []).map(option => toNode(option, onlyEnabled));
      setTreeData(current => append ? mergeNodes(current, nodes) : nodes);
      setRootPage(result.number ?? page);
      setRootHasNext(Boolean(result.hasNext));
    } catch {
      if (sequence === requestSequence.current) setLoadError(true);
    } finally {
      if (sequence === requestSequence.current) setLoading(false);
    }
  }, [onlyEnabled, requestOptions]);

  const loadSearch = useCallback(async (keyword: string, page = 0, append = false) => {
    const normalized = keyword.trim();
    if (!normalized) return;
    const sequence = ++requestSequence.current;
    setLoading(true);
    setLoadError(false);
    try {
      const result = await requestOptions({keyword: normalized, page: String(page)});
      if (sequence !== requestSequence.current) return;
      const nodes = (result.content ?? []).map(option => ({
        ...toNode(option, onlyEnabled),
        isLeaf: true,
      }));
      setSearchData(current => append ? mergeNodes(current, nodes) : nodes);
      setSearchPage(result.number ?? page);
      setSearchHasNext(Boolean(result.hasNext));
    } catch {
      if (sequence === requestSequence.current) setLoadError(true);
    } finally {
      if (sequence === requestSequence.current) setLoading(false);
    }
  }, [onlyEnabled, requestOptions]);

  const loadChildren = useCallback(async (node: OrganizationNode) => {
    if (node.children || node.isLeaf) return;
    setLoading(true);
    setLoadError(false);
    try {
      const result = await requestOptions({parentId: node.value, page: '0', size: '100'});
      const children = (result.content ?? []).map(option => toNode(option, onlyEnabled));
      setTreeData(current => setChildren(current, node.value, children));
    } catch {
      setLoadError(true);
    } finally {
      setLoading(false);
    }
  }, [onlyEnabled, requestOptions]);

  useEffect(() => {
    requestSequence.current += 1;
    setTreeData([]);
    setSearchData([]);
    setSearchValue('');
    setRootPage(-1);
    setRootHasNext(false);
    setSearchPage(-1);
    setSearchHasNext(false);
    setLoadError(false);
  }, [scope, endpoint, excludeId, onlyEnabled, pageSize]);

  useEffect(() => {
    const selectedId = value?.trim();
    if (!selectedId) {
      setSelected(undefined);
      return;
    }
    let cancelled = false;
    void requestOptions({ids: selectedId, page: '0'})
      .then(result => {
        if (!cancelled) setSelected(result.content?.[0]);
      })
      .catch(() => {
        if (!cancelled) setSelected(undefined);
      });
    return () => {
      cancelled = true;
    };
  }, [requestOptions, scope, value]);

  useEffect(() => () => {
    requestSequence.current += 1;
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
  }, []);

  const labelledValue = useMemo(() => {
    if (!value) return undefined;
    return {value, label: selected ? labelOf(selected) : value};
  }, [selected, value]);

  const visibleTreeData = searchValue.trim() ? searchData : treeData;

  return (
    <TreeSelect
      {...rest}
      value={labelledValue}
      labelInValue
      treeData={visibleTreeData}
      loadData={loadChildren as TreeSelectProps['loadData']}
      showSearch
      filterTreeNode={false}
      allowClear={allowClear}
      disabled={disabled}
      loading={loading}
      placeholder={placeholder ?? t('organizations.selector.searchPlaceholder', '搜索或选择组织')}
      searchValue={searchValue}
      onOpenChange={(open) => {
        if (open && treeData.length === 0 && !loading) void loadRoots();
      }}
      onSearch={(keyword) => {
        setSearchValue(keyword);
        if (debounceTimer.current) clearTimeout(debounceTimer.current);
        if (!keyword.trim()) {
          requestSequence.current += 1;
          setSearchData([]);
          setSearchPage(-1);
          setSearchHasNext(false);
          setLoading(false);
          return;
        }
        debounceTimer.current = setTimeout(() => void loadSearch(keyword), debounceMs);
      }}
      onPopupScroll={(event) => {
        const target = event.currentTarget;
        if (target.scrollTop + target.clientHeight < target.scrollHeight - 24 || loading) return;
        if (searchValue.trim() && searchHasNext) {
          void loadSearch(searchValue, searchPage + 1, true);
        } else if (!searchValue.trim() && rootHasNext) {
          void loadRoots(rootPage + 1, true);
        }
      }}
      onChange={(next) => {
        const nextValue = next && typeof next === 'object' && 'value' in next
          ? String(next.value)
          : undefined;
        const matched = [...treeData, ...searchData]
          .find(node => node.value === nextValue);
        if (matched && nextValue) {
          setSelected({
            id: nextValue,
            name: matched.title,
            enabled: !matched.disabled,
            hasChildren: !matched.isLeaf,
          });
        } else if (!nextValue) {
          setSelected(undefined);
        }
        onChange?.(nextValue);
      }}
      notFoundContent={loading
        ? <Spin size="small" />
        : loadError
          ? <Typography.Text type="danger">{t('form.remoteSelect.loadFailed', '加载失败，请重新搜索')}</Typography.Text>
          : t('table.emptyText', '暂无数据')}
    />
  );
}
