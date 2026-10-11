import {TreeSelect, Spin, Typography} from 'antd';
import type {TreeSelectProps} from 'antd';
import {useCallback, useEffect, useMemo, useRef, useState, type ReactNode} from 'react';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useOwnedReads} from '@simplepoint/shared/hooks/useOwnedReads';
import TableActions from '../Table/Actions';
import {branchChannel, rootChannel, searchChannel, organizationId, organizationLabel, organizationPage,
  organizationProjection, optionRows, mergeOptionWindow, putOptionWindow,
  type OrganizationOption, type OptionWindow, type OrganizationProjection} from './model';
export type {OrganizationOption} from './model';

type OrganizationNode = {
  key: string;
  value: string;
  title: ReactNode;
  isLeaf: boolean;
  disabled?: boolean;
  selectable?: boolean;
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

function positiveInteger(value: number | undefined, fallback: number) {
  return Number.isSafeInteger(value) && Number(value) > 0 ? Number(value) : fallback;
}

/**
 * Shared tenant organization picker.
 *
 * Roots and direct children are loaded lazily. Keyword searches are served by
 * the backend in count-free pages, so the component stays bounded for very
 * large organization trees.
 */
export default function OrganizationSelect({
  pageSize: requestedPageSize, debounceMs: requestedDebounceMs, ...props
}: OrganizationSelectProps) {
  const scope = useQueryScope();
  const pageSize = Math.min(positiveInteger(requestedPageSize, DEFAULT_PAGE_SIZE), 100);
  const debounceMs = Math.min(positiveInteger(requestedDebounceMs, DEFAULT_DEBOUNCE_MS), 60_000);
  const owner = JSON.stringify([scope, props.endpoint ?? DEFAULT_ENDPOINT, props.excludeId, props.onlyEnabled, pageSize]);
  return <OrganizationSelectSession key={owner} {...props} owner={owner} pageSize={pageSize} debounceMs={debounceMs}/>;
}

type ReadPage = {number: number; direction: 'replace' | 'next' | 'previous'; keyword?: string; parentId?: string};

function OrganizationSelectSession({
  value,
  onChange,
  endpoint = DEFAULT_ENDPOINT,
  excludeId,
  pageSize,
  debounceMs,
  owner,
  onlyEnabled = false,
  allowClear = true,
  placeholder,
  disabled,
  onOpenChange,
  onPopupScroll,
  onTreeExpand,
  popupRender,
  ...rest
}: OrganizationSelectProps & {owner: string; pageSize: number; debounceMs: number}) {
  const {t} = useI18n();
  const operation = useOperationScope(owner);
  const reads = useOwnedReads(operation, true);
  const cacheRef = useRef(new Map<string, OptionWindow>());
  const [cache, setCache] = useState(cacheRef.current);
  const requests = useRef(new Map<string, ReadPage>());
  const pendingPages = useRef(new Map<string, Promise<void>>());
  const [selected, setSelected] = useState<OrganizationOption>();
  const [lookupDone, setLookupDone] = useState<string>();
  const [open, setOpen] = useState(false);
  const openRef = useRef(false);
  const [searchValue, setSearchValue] = useState('');
  const searchRef = useRef('');
  const [keyword, setKeyword] = useState('');
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  const mayBrowse = () => openRef.current && reads.active && !disabled && operation.current() && operation.isSelected();
  const cancelPages = useCallback((prefix = 'browse:') => {
    reads.cancel(prefix);
    for (const key of pendingPages.current.keys()) if (key.startsWith(prefix)) pendingPages.current.delete(key);
  }, [reads.cancel]);
  const requestOptions = useCallback(async (params: Record<string, string>, signal: AbortSignal) => {
    const result = organizationPage(await get<unknown>(endpoint, {
      size: String(pageSize),
      ...(excludeId ? {excludeId} : {}),
      ...params,
    }, {signal, notifyOnError: false}), Number(params.page));
    return {...result, content: result.content.filter(option => option.id !== excludeId)};
  }, [endpoint, excludeId, pageSize]);
  const installWindow = (channel: string, window: OptionWindow) => {
    const result = putOptionWindow(cacheRef.current, channel, window);
    const evictedParents = result.evicted.map(key => requests.current.get(key)?.parentId);
    result.evicted.forEach(key => {cancelPages(key); reads.clearErrors(key); requests.current.delete(key);});
    cacheRef.current = result.cache; setCache(result.cache);
    const reachable = new Set<string>();
    const visit = (nodes: OrganizationProjection[]) => nodes.forEach(node => {reachable.add(node.option.id); visit(node.children ?? []);});
    visit(organizationProjection(result.cache));
    setExpandedKeys(previous => previous.filter(id => reachable.has(id) && !evictedParents.includes(id)));
  };
  const loadPage = (channel: string, descriptor: ReadPage) => {
    if (!mayBrowse() || (descriptor.keyword ?? '') !== searchRef.current) return Promise.resolve();
    const pending = pendingPages.current.get(channel); if (pending) return pending;
    // Reserve even an in-flight branch so requests, errors and empty branches have the same bounded lifetime.
    if (!cacheRef.current.has(channel)) installWindow(channel, {pages: []});
    requests.current.set(channel, descriptor);
    const promise = reads.run(channel, signal => requestOptions({page: String(descriptor.number),
      ...(descriptor.keyword ? {keyword: descriptor.keyword} : {}),
      ...(descriptor.parentId ? {parentId: descriptor.parentId} : {})}, signal), page => {
      installWindow(channel, mergeOptionWindow(cacheRef.current.get(channel), page, descriptor.direction));
    }).finally(() => {if (pendingPages.current.get(channel) === promise) pendingPages.current.delete(channel);});
    pendingPages.current.set(channel, promise); return promise;
  };
  const selectedId = organizationId(value);
  const loadSelected = useCallback(() => {
    if (!selectedId || !reads.active) return;
    const known = [...cacheRef.current.values()].flatMap(optionRows).find(option => option.id === selectedId);
    if (known) {setSelected(known); setLookupDone(selectedId); return;}
    void reads.run('lookup:', signal => requestOptions({ids: selectedId, page: '0'}, signal), page => {
      setSelected(page.content.find(option => option.id === selectedId)); setLookupDone(selectedId);
    });
  }, [selectedId, reads.active, reads.run, requestOptions]);
  useEffect(() => {
    reads.cancel('lookup:'); reads.clearErrors('lookup:'); setLookupDone(undefined);
    if (selected?.id !== selectedId) setSelected(undefined);
    loadSelected();
    return () => reads.cancel('lookup:');
  }, [loadSelected, reads.cancel, reads.clearErrors]);
  useEffect(() => {
    if (!reads.active || disabled) {openRef.current = false; setOpen(false); cancelPages();}
  }, [reads.active, disabled, cancelPages]);
  useEffect(() => {
    if (!open || !reads.active || disabled) return;
    const timer = setTimeout(() => setKeyword(searchValue.trim().slice(0, 128)), debounceMs);
    return () => clearTimeout(timer);
  }, [searchValue, open, reads.active, disabled, debounceMs]);
  const currentChannel = searchRef.current ? searchChannel : rootChannel;
  useEffect(() => {
    if (!mayBrowse() || searchRef.current !== keyword || cacheRef.current.get(currentChannel)?.pages.length || reads.errors[currentChannel]) return;
    void loadPage(currentChannel, {number: 0, direction: 'replace', ...(keyword ? {keyword} : {})});
  }, [open, reads.active, disabled, keyword, currentChannel, reads.errors]);

  const projected = useMemo(() => organizationProjection(cache), [cache]);
  const visibleOptions = useMemo(() => {
    if (searchRef.current) return optionRows(cache.get(searchChannel));
    const rows: OrganizationOption[] = [];
    const visit = (nodes: OrganizationProjection[]) => nodes.forEach(node => {rows.push(node.option); visit(node.children ?? []);});
    visit(projected); return rows;
  }, [cache, projected, searchValue]);
  const optionIndex = useMemo(() => new Map(visibleOptions.map(option => [option.id, option])), [visibleOptions]);
  const navigation = (channel: string) => {
    const window = cache.get(channel), first = window?.pages[0], last = window?.pages[(window?.pages.length ?? 0) - 1];
    const descriptor = requests.current.get(channel) ?? {number: 0, direction: 'replace' as const};
    const actions: Array<{key: string; text: string; page: ReadPage}> = [];
    if (reads.errors[channel]) actions.push({key: 'retry', text: t('table.retry', '重试'), page: descriptor});
    else {
      if (first && first.number > 0) actions.push({key: 'previous', text: t('form.remoteSelect.previous', '加载更早的结果'),
        page: {...descriptor, number: first.number - 1, direction: 'previous'}});
      if (last?.hasNext) actions.push({key: 'more', text: t('form.remoteSelect.more', '加载更多'),
        page: {...descriptor, number: last.number + 1, direction: 'next'}});
    }
    return actions;
  };
  const actionButton = (channel: string, action: ReturnType<typeof navigation>[number]) => <div
    data-organization-navigation={channel} data-organization-action={action.key}
    onMouseDown={event => event.preventDefault()} onClick={event => event.stopPropagation()}>
    <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: action.key, sort: 0, text: action.text, type: 'link', size: 'small',
      danger: action.key === 'retry', title: action.key === 'retry' ? t('table.loadFail', '加载失败') : undefined,
      loading: reads.loading.includes(channel), disabled: !open || !reads.active || disabled || reads.loading.includes(channel),
      onClick: () => {void loadPage(channel, action.page);}}]}/>
  </div>;
  const nodeFor = (option: OrganizationOption, leaf = false): OrganizationNode => ({
    key: option.id, value: option.id, title: <span data-organization-id={option.id}>{organizationLabel(option)}</span>,
    isLeaf: leaf || !option.hasChildren, disabled: onlyEnabled && !option.enabled,
  });
  const navKeys = new Set(optionIndex.keys());
  const treeNodes = (nodes: OrganizationProjection[]): OrganizationNode[] => nodes.map(({option, children}) => {
    const channel = branchChannel(option.id), actions = navigation(channel);
    const navigationNodes = actions.map(action => {
      let key = JSON.stringify(['organization-navigation', channel, action.key]);
      while (navKeys.has(key)) key += ':'; navKeys.add(key);
      return {key, value: key, title: actionButton(channel, action), isLeaf: true, selectable: false};
    });
    return {...nodeFor(option), ...(children || navigationNodes.length ? {children: [...treeNodes(children ?? []), ...navigationNodes]} : {})};
  });
  const treeData = searchRef.current ? visibleOptions.map(option => nodeFor(option, true)) : treeNodes(projected);
  const loadedKeys = visibleOptions.filter(option => cache.get(branchChannel(option.id))?.pages.length
    || reads.errors[branchChannel(option.id)]).map(option => option.id);
  const loadChildren = (node: OrganizationNode) => {
    if (!mayBrowse() || searchRef.current || node.isLeaf || !optionIndex.has(node.value)) return Promise.resolve();
    const channel = branchChannel(node.value), window = cacheRef.current.get(channel);
    if (window?.pages.length) {installWindow(channel, window); return Promise.resolve();}
    if (reads.errors[channel]) return Promise.resolve();
    return loadPage(channel, {parentId: node.value, number: 0, direction: 'replace'});
  };
  const loading = reads.loading.includes(currentChannel) || (!!searchRef.current && keyword !== searchRef.current);
  const labelledValue = value ? {value, label: selected?.id === value ? organizationLabel(selected) : value} : undefined;
  const changeOpen = (next: boolean) => {
    const permitted = next && reads.active && !disabled;
    openRef.current = permitted; setOpen(permitted); onOpenChange?.(permitted);
    if (!permitted) {cancelPages(); return;}
    // RcTree can retain expanded keys after hiding; explicitly resume interrupted visible branches.
    if (!searchRef.current) expandedKeys.forEach(id => {const option = optionIndex.get(id); if (option) void loadChildren(nodeFor(option));});
  };
  const loadNext = () => {
    const action = navigation(currentChannel).find(action => action.key === 'more');
    if (action) void loadPage(currentChannel, action.page);
  };
  return <><TreeSelect
      {...rest}
      virtual={rest.virtual ?? true}
      listHeight={rest.listHeight ?? 256}
      value={labelledValue}
      labelInValue
      treeData={treeData}
      loadData={loadChildren as TreeSelectProps['loadData']}
      treeLoadedKeys={loadedKeys}
      treeExpandedKeys={expandedKeys}
      onTreeExpand={keys => {
        const next = keys.filter((key): key is string => typeof key === 'string' && optionIndex.has(key));
        expandedKeys.filter(id => !next.includes(id)).forEach(id => cancelPages(branchChannel(id)));
        setExpandedKeys(next); onTreeExpand?.(keys);
        next.filter(id => !expandedKeys.includes(id)).forEach(id => {const option = optionIndex.get(id); if (option) void loadChildren(nodeFor(option));});
      }}
      open={open && reads.active && !disabled}
      showSearch
      filterTreeNode={false}
      allowClear={allowClear}
      disabled={disabled}
      loading={loading}
      placeholder={placeholder ?? t('organizations.selector.searchPlaceholder', '搜索或选择组织')}
      searchValue={searchValue}
      onOpenChange={changeOpen}
      onSearch={(keyword) => {
        cancelPages(); reads.clearErrors(searchChannel); requests.current.delete(searchChannel);
        cacheRef.current = new Map(cacheRef.current); cacheRef.current.delete(searchChannel); setCache(cacheRef.current);
        searchRef.current = keyword.trim().slice(0, 128); setSearchValue(keyword);
        if (!searchRef.current) setKeyword('');
      }}
      onPopupScroll={(event) => {
        onPopupScroll?.(event);
        const target = event.currentTarget;
        if (target.scrollTop + target.clientHeight >= target.scrollHeight - 24 && !loading) loadNext();
      }}
      onChange={(next) => {
        if (!reads.active || disabled || !operation.current() || !operation.isSelected()) return;
        const nextValue = next && typeof next === 'object' && 'value' in next
          ? organizationId(next.value)
          : undefined;
        const matched = nextValue ? optionIndex.get(nextValue) : undefined;
        if (nextValue && (!matched || nextValue === excludeId || (onlyEnabled && !matched.enabled))) return;
        if (next && !nextValue) return;
        reads.cancel('lookup:'); setSelected(matched); setLookupDone(nextValue);
        onChange?.(nextValue);
      }}
      notFoundContent={loading
        ? <Spin size="small" />
        : reads.errors[currentChannel]
          ? <Typography.Text type="danger">{t('form.remoteSelect.loadFailed', '加载失败，请重新搜索')}</Typography.Text>
          : t('table.emptyText', '暂无数据')}
      popupRender={menu => <>{popupRender ? popupRender(menu) : menu}<div style={{padding: 8}}
        onMouseDown={event => event.preventDefault()}>
        {!!reads.errors[currentChannel] && <Typography.Text type="danger">{t('table.loadFail', '加载失败')}</Typography.Text>}
        {navigation(currentChannel).map(action => <span key={action.key}>{actionButton(currentChannel, action)}</span>)}
      </div></>}
    />{selectedId && reads.errors['lookup:'] ? <div className="organization-select-lookup-error" role="alert">
      <Typography.Text type="danger">{t('form.remoteSelect.selectedLoadFailed', '已选项加载失败，原值已保留')}</Typography.Text>
      <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, type: 'link', text: t('table.retry', '重试'),
        disabled: !reads.active || reads.loading.includes('lookup:'), onClick: loadSelected}]}/>
    </div> : selectedId && lookupDone === selectedId && !selected
      ? <Typography.Text type="warning" role="status">{t('form.remoteSelect.selectedUnavailableHint', '部分已选项已删除或无权访问，原值已保留，请重新选择')}</Typography.Text>
      : null}</>;
}
