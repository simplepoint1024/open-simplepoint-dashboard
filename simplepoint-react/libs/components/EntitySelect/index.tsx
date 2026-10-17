import {useEffect, useMemo, useState, type ReactNode} from 'react';
import {Button, Select, Spin, Typography} from 'antd';
import {useInfiniteQuery, useQueryClient} from '@tanstack/react-query';
import {get, useData} from '@simplepoint/shared/api/methods';
import {scopedQueryOptions} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageActive} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {normalizePage, type Page, type RawPage} from '@simplepoint/shared/types/request';
import {entityLookupBatches, entitySearchParams, mergeEntityOptions, type EntityOption} from './query';
import {cancelInactiveEntityRead} from './queryLifecycle';

/** Adapts a bounded domain directory without creating a second selection implementation. */
export type EntitySelectSource = {
  key: string;
  minSearchLength?: number;
  pageSize?: number;
  debounceMs?: number;
  searchParams: (keyword: string, page: number, size: number) => Record<string, string | number>;
  option: (row: Record<string, unknown>) => EntityOption | undefined;
  resolve?: (ids: string[], signal: AbortSignal) => Promise<EntityOption[]>;
};

interface EntitySelectBaseProps {
  endpoint: string;
  filters?: Record<string, string>;
  /** Browse eligibility can differ from hydration of an existing readable value. Defaults to filters. */
  lookupFilters?: Record<string, string>;
  disabled?: boolean;
  allowClear?: boolean;
  placeholder?: string;
  searchHint?: string;
  id?: string;
  className?: string;
  ariaLabel?: string;
  source?: EntitySelectSource;
  /** Domain renderers receive the same authorized metadata for search and exact hydration. */
  renderOption?: (option: EntityOption) => ReactNode;
  renderSelected?: (option: EntityOption) => ReactNode;
  status?: 'error' | 'warning';
  maxTagCount?: number | 'responsive';
  autoFocus?: boolean;
  defaultOpen?: boolean;
  onBlur?: () => void;
  onFocus?: () => void;
}

export type EntitySelectProps = EntitySelectBaseProps & ({
  multiple?: false;
  value?: string | null;
  onChange?: (value: string | undefined, option?: EntityOption) => void;
} | {
  multiple: true;
  value?: string[];
  maxCount?: number;
  onChange?: (value: string[]) => void;
});

type EntityRow = {id: string; name?: string | null};

/** Bounded, authorized server pages; searches and selected-value lookup never materialize a catalog. */
export default function EntitySelect(props: EntitySelectProps) {
  const scope = useQueryScope();
  return <EntitySelectSession key={JSON.stringify([scope, props.endpoint, props.source?.key, props.source?.pageSize,
    props.source?.debounceMs, props.source?.minSearchLength, props.filters, props.lookupFilters])} {...props}/>;
}

function EntitySelectSession(props: EntitySelectProps) {
  const {endpoint, value, filters, lookupFilters = filters, disabled, allowClear = true, placeholder, id, className, ariaLabel, source} = props;
  const {t} = useI18n();
  const scope = useQueryScope();
  const active = usePageActive();
  const client = useQueryClient();
  const pageSize = Number.isSafeInteger(source?.pageSize) && source!.pageSize! > 0 ? Math.min(source!.pageSize!, 100) : 20;
  const debounceMs = Number.isSafeInteger(source?.debounceMs) && source!.debounceMs! > 0 ? Math.min(source!.debounceMs!, 60000) : 250;
  const [open, setOpen] = useState(props.defaultOpen ?? false);
  const [search, setSearch] = useState('');
  const [keyword, setKeyword] = useState('');
  const [selected, setSelected] = useState<EntityOption[]>([]);
  const pageKey = ['entity-select', endpoint, source?.key, filters, keyword, pageSize];
  useEffect(() => {
    if (!active) return;
    const timer = setTimeout(() => setKeyword(search), debounceMs);
    return () => clearTimeout(timer);
  }, [search, debounceMs, active]);
  const pages = useInfiniteQuery({
    ...scopedQueryOptions<Page<EntityRow>, number>(scope, pageKey, async ({pageParam, signal}) =>
      normalizePage(await get<RawPage<EntityRow>>(endpoint,
        {...(source?.searchParams ?? entitySearchParams)(keyword, pageParam, pageSize), ...filters}, {signal, notifyOnError: false}))),
    initialPageParam: 0,
    getNextPageParam: last => last.page.number + 1 < last.page.totalPages ? last.page.number + 1 : undefined,
    getPreviousPageParam: first => first.page.number > 0 ? first.page.number - 1 : undefined,
    maxPages: 10,
    enabled: active && open && !disabled && search === keyword
      && keyword.trim().length >= (source?.minSearchLength ?? 0),
    staleTime: 30_000,
    gcTime: 120_000,
    retry: false,
  });
  const options = useMemo(() => {
    const rows = pages.data?.pages.flatMap(page => page.content) ?? [];
    return source ? rows.flatMap(row => {
      const option = source.option(row);
      return option ? [option] : [];
    }) : mergeEntityOptions(rows);
  }, [pages.data, source]);
  const values = Array.isArray(value) ? value : value ? [value] : [];
  const known = new Map([...selected, ...options].map(option => [option.value, option]));
  const missing = values.filter(id => !known.has(id));
  const lookupKey = ['entity-select-selected-v4', endpoint, source?.key, lookupFilters, missing];
  const lookup = useData(lookupKey, async ({signal}) => {
    if (source?.resolve) return source.resolve(missing, signal);
    const rows: EntityRow[] = [];
    // Sequential bounded batches avoid a request burst for large pre-existing selections.
    for (const params of entityLookupBatches(missing)) {
      rows.push(...normalizePage(await get<RawPage<EntityRow>>(endpoint, {...params, ...lookupFilters}, {signal, notifyOnError: false})).content);
    }
    return mergeEntityOptions(rows);
  }, {enabled: missing.length > 0, retry: false, staleTime: 30_000, gcTime: 120_000});
  const pageIdentity = JSON.stringify(pageKey), lookupIdentity = JSON.stringify(lookupKey);
  useEffect(() => {
    // Another selector can share this directory read. Only cancel once no enabled owner needs it.
    if (!active || !open || disabled || search !== keyword) void cancelInactiveEntityRead(client, scope, pageKey);
    if (!active) void cancelInactiveEntityRead(client, scope, lookupKey);
  }, [active, open, disabled, search, keyword, pageIdentity, lookupIdentity, client]);
  const resolved = new Map([...(lookup.data ?? []), ...known.values()].map(option => [option.value, option]));
  const visible = search === keyword ? options : [];
  const mayBrowse = active && open && !disabled && search === keyword;
  const loadMore = () => {if (mayBrowse && !pages.isFetching && pages.hasNextPage) void pages.fetchNextPage();};
  const loadPrevious = () => {if (mayBrowse && !pages.isFetching && pages.hasPreviousPage) void pages.fetchPreviousPage();};
  const failure = pages.error;
  const unavailable = values.filter(id => !resolved.has(id));
  return <><Select<string | string[]>
    id={id} className={className} aria-label={ariaLabel} style={{width: '100%'}} showSearch filterOption={false} autoFocus={props.autoFocus}
    allowClear={allowClear} disabled={disabled} value={value || undefined} placeholder={placeholder} status={props.status}
    maxTagCount={props.maxTagCount} onBlur={props.onBlur} onFocus={props.onFocus}
    mode={props.multiple ? 'multiple' : undefined} maxCount={props.multiple ? props.maxCount : undefined}
    optionRender={props.renderOption ? option => props.renderOption!(option.data as EntityOption) : undefined}
    labelRender={label => {
      const option = resolved.get(String(label.value));
      return option ? props.renderSelected?.(option) ?? option.label
        : lookup.isFetching ? t('form.remoteSelect.selectedLoading', '正在加载已选项')
          : t('form.remoteSelect.selectedUnavailable', '已选项不可用');
    }}
    options={visible} open={open && active && !disabled}
    loading={pages.isFetching || lookup.isFetching || search !== keyword} searchValue={search}
    onOpenChange={next => {setOpen(next); if (!next) setSearch('');}}
    onSearch={setSearch}
    onChange={next => {
      if (!active || disabled) return;
      const ids = Array.isArray(next) ? next : next ? [next] : [];
      setSelected(ids.flatMap(id => resolved.has(id) ? [resolved.get(id)!] : []));
      if (props.multiple) props.onChange?.(ids);
      else props.onChange?.(ids[0], resolved.get(ids[0]));
    }}
    onPopupScroll={event => {
      const target = event.currentTarget;
      if (target.scrollTop + target.clientHeight >= target.scrollHeight - 24) loadMore();
    }}
    notFoundContent={search.trim().length < (source?.minSearchLength ?? 0) && props.searchHint
      ? <Typography.Text type="secondary">{props.searchHint}</Typography.Text>
      : pages.isFetching || search !== keyword ? <Spin size="small"/> : t('form.remoteSelect.empty', '没有匹配的数据')}
    popupRender={menu => <>{menu}<div style={{padding: 8}} onMouseDown={event => event.preventDefault()}>
      {pages.hasPreviousPage && !failure && <Button block loading={pages.isFetchingPreviousPage}
        disabled={pages.isFetching && !pages.isFetchingPreviousPage} onClick={loadPrevious}>
        {t('form.remoteSelect.previous', '加载更早的结果')}
      </Button>}
      {failure ? <><Typography.Text type="danger">{t('table.loadFail', '加载失败')}</Typography.Text>
        <Button type="link" disabled={!mayBrowse || pages.isFetching} onClick={() => {if (mayBrowse) void (pages.isFetchNextPageError ? pages.fetchNextPage() : pages.refetch());}}>{t('table.retry', '重试')}</Button></>
        : pages.hasNextPage ? <Button block loading={pages.isFetchingNextPage} onClick={loadMore}>{t('form.remoteSelect.more', '加载更多')}</Button> : null}
    </div></>}
  />{missing.length > 0 && lookup.error ? <div className="entity-select-lookup-error" role="alert">
    <Typography.Text type="danger">{t('form.remoteSelect.selectedLoadFailed', '已选项加载失败，原值已保留')}</Typography.Text>
    <Button type="link" disabled={lookup.isFetching || !active} onClick={() => {void lookup.refetch();}}>{t('table.retry', '重试')}</Button>
  </div> : unavailable.length > 0 && lookup.isSuccess && !lookup.isFetching
    ? <Typography.Text type="warning" role="status">{t('form.remoteSelect.selectedUnavailableHint', '部分已选项已删除或无权访问，原值已保留，请重新选择')}</Typography.Text>
    : null}</>;
}
