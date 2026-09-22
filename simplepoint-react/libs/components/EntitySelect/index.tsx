import {useEffect, useMemo, useState} from 'react';
import {Button, Select, Spin, Typography} from 'antd';
import {useInfiniteQuery} from '@tanstack/react-query';
import {get, useData} from '@simplepoint/shared/api/methods';
import {scopedQueryOptions} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageActive} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {normalizePage, type Page, type RawPage} from '@simplepoint/shared/types/request';
import {entityLookupBatches, entitySearchParams, mergeEntityOptions, type EntityOption} from './query';

interface EntitySelectBaseProps {
  endpoint: string;
  filters?: Record<string, string>;
  disabled?: boolean;
  allowClear?: boolean;
  placeholder?: string;
  id?: string;
  className?: string;
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
const pageSize = 20;

/** Bounded, authorized server pages; searches and selected-value lookup never materialize a catalog. */
export default function EntitySelect(props: EntitySelectProps) {
  const scope = useQueryScope();
  return <EntitySelectSession key={JSON.stringify([scope, props.endpoint, props.filters])} {...props}/>;
}

function EntitySelectSession(props: EntitySelectProps) {
  const {endpoint, value, filters, disabled, allowClear = true, placeholder, id, className} = props;
  const {t} = useI18n();
  const scope = useQueryScope();
  const active = usePageActive();
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [keyword, setKeyword] = useState('');
  const [selected, setSelected] = useState<EntityOption[]>([]);
  useEffect(() => {
    const timer = setTimeout(() => setKeyword(search), 250);
    return () => clearTimeout(timer);
  }, [search]);
  const pages = useInfiniteQuery({
    ...scopedQueryOptions<Page<EntityRow>, number>(scope, ['entity-select', endpoint, filters, keyword], async ({pageParam, signal}) =>
      normalizePage(await get<RawPage<EntityRow>>(endpoint, {...entitySearchParams(keyword, pageParam, pageSize), ...filters}, {signal}))),
    initialPageParam: 0,
    getNextPageParam: last => last.page.number + 1 < last.page.totalPages ? last.page.number + 1 : undefined,
    enabled: active && open && !disabled && search === keyword,
    retry: false,
  });
  const options = useMemo(() => mergeEntityOptions(pages.data?.pages.flatMap(page => page.content) ?? []), [pages.data]);
  const values = Array.isArray(value) ? value : value ? [value] : [];
  const known = new Map([...selected, ...options].map(option => [option.value, option]));
  const missing = values.filter(id => !known.has(id));
  const lookup = useData(['entity-select-selected-v2', endpoint, missing], async ({signal}) => {
    const rows: EntityRow[] = [];
    // Sequential bounded batches avoid a request burst for large pre-existing selections.
    for (const params of entityLookupBatches(missing)) {
      rows.push(...normalizePage(await get<RawPage<EntityRow>>(endpoint, params, {signal})).content);
    }
    return rows;
  }, {enabled: missing.length > 0, retry: false});
  const resolved = new Map([...mergeEntityOptions(lookup.data ?? []), ...known.values()].map(option => [option.value, option]));
  const visible = search === keyword ? options : [];
  const loadMore = () => {if (search === keyword && !pages.isFetching && pages.hasNextPage) void pages.fetchNextPage();};
  const failure = pages.error;
  return <><Select<string | string[]>
    id={id} className={className} style={{width: '100%'}} showSearch filterOption={false}
    allowClear={allowClear} disabled={disabled} value={value || undefined} placeholder={placeholder}
    mode={props.multiple ? 'multiple' : undefined} maxCount={props.multiple ? props.maxCount : undefined}
    labelRender={label => resolved.get(String(label.value))?.label ?? label.label ?? label.value}
    options={visible} open={open && active && !disabled}
    loading={pages.isFetching || lookup.isFetching || search !== keyword} searchValue={search}
    onOpenChange={next => {setOpen(next); if (!next) setSearch('');}}
    onSearch={setSearch}
    onChange={next => {
      const ids = Array.isArray(next) ? next : next ? [next] : [];
      setSelected(ids.flatMap(id => resolved.has(id) ? [resolved.get(id)!] : []));
      if (props.multiple) props.onChange?.(ids);
      else props.onChange?.(ids[0], resolved.get(ids[0]));
    }}
    onPopupScroll={event => {
      const target = event.currentTarget;
      if (target.scrollTop + target.clientHeight >= target.scrollHeight - 24) loadMore();
    }}
    notFoundContent={pages.isFetching ? <Spin size="small"/> : t('form.remoteSelect.empty', '没有匹配的数据')}
    popupRender={menu => <>{menu}<div style={{padding: 8}} onMouseDown={event => event.preventDefault()}>
      {failure ? <><Typography.Text type="danger">{t('table.loadFail', '加载失败')}</Typography.Text>
        <Button type="link" onClick={() => {void (pages.isFetchNextPageError ? pages.fetchNextPage() : pages.refetch());}}>{t('table.retry', '重试')}</Button></>
        : pages.hasNextPage ? <Button block loading={pages.isFetchingNextPage} onClick={loadMore}>{t('form.remoteSelect.more', '加载更多')}</Button> : null}
    </div></>}
  />{missing.length > 0 && lookup.error ? <div className="entity-select-lookup-error" role="alert">
    <Typography.Text type="danger">{t('form.remoteSelect.selectedLoadFailed', '已选项加载失败，原值已保留')}</Typography.Text>
    <Button type="link" disabled={disabled} onClick={() => {void lookup.refetch();}}>{t('table.retry', '重试')}</Button>
  </div> : null}</>;
}
