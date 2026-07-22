import type {WidgetProps} from '@rjsf/utils';
import {Select, Spin, Typography} from 'antd';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';

type UserPickerItem = {
  id?: unknown;
  name?: unknown;
  email?: unknown;
  phoneNumber?: unknown;
};

type UserPickerOption = {
  value: string;
  label: string;
};

type UserPickerConfig = {
  selectionMode?: unknown;
  endpoint?: unknown;
  resolveEndpoint?: unknown;
  pageSize?: unknown;
  debounceMs?: unknown;
  minSearchLength?: unknown;
  pageParam?: unknown;
  sizeParam?: unknown;
  searchParam?: unknown;
  resolveParam?: unknown;
  staticParams?: unknown;
};

const DEFAULT_ENDPOINT = '/common/users/picker/items';
const DEFAULT_RESOLVE_ENDPOINT = '/common/users/picker/selected';
const DEFAULT_PAGE_SIZE = 20;
const DEFAULT_DEBOUNCE_MS = 350;
const DEFAULT_MIN_SEARCH_LENGTH = 3;
const selectedUserCache = new Map<string, UserPickerOption>();

function positiveInteger(value: unknown, fallback: number) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function fieldName(value: unknown, fallback: string) {
  return typeof value === 'string' && value.trim() ? value.trim() : fallback;
}

function endpoint(value: unknown, fallback: string) {
  return typeof value === 'string' && value.startsWith('/') ? value : fallback;
}

function plainRecord(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  return Object.fromEntries(
    Object.entries(value)
      .filter(([, item]) => ['string', 'number', 'boolean'].includes(typeof item))
      .map(([key, item]) => [key, String(item)]),
  );
}

function normalizeIds(value: unknown, multiple: boolean): string[] {
  const values = multiple ? (Array.isArray(value) ? value : []) : [value];
  return [...new Set(values
    .filter((item) => typeof item === 'string' || typeof item === 'number')
    .map(String)
    .map((item) => item.trim())
    .filter(Boolean))];
}

function toOption(item: UserPickerItem): UserPickerOption | undefined {
  const id = item.id == null ? '' : String(item.id).trim();
  if (!id) return undefined;
  const name = item.name == null ? '' : String(item.name).trim();
  const email = item.email == null ? '' : String(item.email).trim();
  const phoneNumber = item.phoneNumber == null ? '' : String(item.phoneNumber).trim();
  const primary = name || email || phoneNumber || id;
  const secondary = [...new Set([email, phoneNumber].filter((part) => part && part !== primary))];
  return {value: id, label: secondary.length > 0 ? `${primary} · ${secondary.join(' · ')}` : primary};
}

function mergeOptions(...groups: UserPickerOption[][]) {
  const options = new Map<string, UserPickerOption>();
  groups.flat().forEach((option) => options.set(option.value, option));
  return [...options.values()];
}

const UserPicker = (props: WidgetProps) => {
  const {
    id,
    value,
    required,
    disabled,
    readonly,
    onChange,
    onBlur,
    onFocus,
    options,
    placeholder,
    rawErrors,
    schema,
  } = props;
  const {t} = useI18n();
  const config = (options ?? {}) as UserPickerConfig;
  const selectionMode = config.selectionMode === 'multiple' ? 'multiple' : 'single';
  const schemaTypes = Array.isArray(schema?.type) ? schema.type : [schema?.type];
  const multiple = selectionMode === 'multiple' || schemaTypes.includes('array');
  const searchEndpoint = endpoint(config.endpoint, DEFAULT_ENDPOINT);
  const resolveEndpoint = endpoint(config.resolveEndpoint, DEFAULT_RESOLVE_ENDPOINT);
  const pageSize = positiveInteger(config.pageSize, DEFAULT_PAGE_SIZE);
  const debounceMs = positiveInteger(config.debounceMs, DEFAULT_DEBOUNCE_MS);
  const minSearchLength = positiveInteger(config.minSearchLength, DEFAULT_MIN_SEARCH_LENGTH);
  const pageParam = fieldName(config.pageParam, 'page');
  const sizeParam = fieldName(config.sizeParam, 'size');
  const searchParam = fieldName(config.searchParam, 'keyword');
  const resolveParam = fieldName(config.resolveParam, 'ids');
  const staticParams = useMemo(() => plainRecord(config.staticParams), [config.staticParams]);
  const configKey = useMemo(() => JSON.stringify({
    searchEndpoint,
    resolveEndpoint,
    pageSize,
    debounceMs,
    minSearchLength,
    pageParam,
    sizeParam,
    searchParam,
    resolveParam,
    staticParams,
  }), [
    debounceMs,
    minSearchLength,
    pageParam,
    pageSize,
    resolveEndpoint,
    resolveParam,
    searchEndpoint,
    searchParam,
    sizeParam,
    staticParams,
  ]);

  const selectedIds = useMemo(() => normalizeIds(value, multiple), [multiple, value]);
  const selectedSignature = selectedIds.join('\u0001');
  const [selectedOptions, setSelectedOptions] = useState<UserPickerOption[]>([]);
  const [remoteOptions, setRemoteOptions] = useState<UserPickerOption[]>([]);
  const [searchText, setSearchText] = useState('');
  const [activeKeyword, setActiveKeyword] = useState('');
  const [loadedPage, setLoadedPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const listRequestSequence = useRef(0);
  const resolveRequestSequence = useRef(0);
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const requestPage = useCallback(async (keyword: string, page: number, append: boolean) => {
    const normalizedKeyword = keyword.trim();
    if (normalizedKeyword.length < minSearchLength) return;
    const requestSequence = ++listRequestSequence.current;
    setLoading(true);
    setLoadError(false);
    try {
      const rawPage = await get<RawPage<UserPickerItem>>(searchEndpoint, {
        ...staticParams,
        [pageParam]: String(page),
        [sizeParam]: String(pageSize),
        [searchParam]: normalizedKeyword,
      });
      if (requestSequence !== listRequestSequence.current) return;
      const result = normalizePage(rawPage, pageSize);
      const nextOptions = result.content
        .map(toOption)
        .filter((item): item is UserPickerOption => !!item);
      nextOptions.forEach((option) => selectedUserCache.set(option.value, option));
      setRemoteOptions((current) => append ? mergeOptions(current, nextOptions) : nextOptions);
      setLoadedPage(result.page.number);
      setTotalPages(result.page.totalPages);
      setActiveKeyword(normalizedKeyword);
    } catch {
      if (requestSequence === listRequestSequence.current) setLoadError(true);
    } finally {
      if (requestSequence === listRequestSequence.current) setLoading(false);
    }
  }, [minSearchLength, pageParam, pageSize, searchEndpoint, searchParam, sizeParam, staticParams]);

  useEffect(() => {
    setRemoteOptions([]);
    setSearchText('');
    setActiveKeyword('');
    setLoadedPage(0);
    setTotalPages(0);
    setLoadError(false);
    listRequestSequence.current += 1;
  }, [configKey]);

  useEffect(() => {
    const cached = selectedIds
      .map((selectedId) => selectedUserCache.get(selectedId))
      .filter((item): item is UserPickerOption => !!item);
    const cachedIds = new Set(cached.map((item) => item.value));
    const missingIds = selectedIds.filter((selectedId) => !cachedIds.has(selectedId));
    if (missingIds.length === 0) {
      const byId = new Map(cached.map((item) => [item.value, item]));
      setSelectedOptions(selectedIds.map((selectedId) => byId.get(selectedId)!).filter(Boolean));
      return;
    }

    const requestSequence = ++resolveRequestSequence.current;
    void get<UserPickerItem[]>(resolveEndpoint, {
      ...staticParams,
      [resolveParam]: missingIds.join(','),
    })
      .then((items) => {
        if (requestSequence !== resolveRequestSequence.current) return;
        (Array.isArray(items) ? items : [])
          .map(toOption)
          .filter((item): item is UserPickerOption => !!item)
          .forEach((item) => selectedUserCache.set(item.value, item));
        setSelectedOptions(selectedIds.map((selectedId) => (
          selectedUserCache.get(selectedId) ?? {value: selectedId, label: selectedId}
        )));
      })
      .catch(() => {
        if (requestSequence === resolveRequestSequence.current) {
          setSelectedOptions(selectedIds.map((selectedId) => (
            selectedUserCache.get(selectedId) ?? {value: selectedId, label: selectedId}
          )));
        }
      });
    return () => {
      resolveRequestSequence.current += 1;
    };
  }, [configKey, resolveEndpoint, resolveParam, selectedSignature, staticParams]);

  useEffect(() => () => {
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    listRequestSequence.current += 1;
    resolveRequestSequence.current += 1;
  }, []);

  const visibleOptions = useMemo(
    () => mergeOptions(selectedOptions, remoteOptions),
    [remoteOptions, selectedOptions],
  );
  const selectValue = multiple
    ? selectedIds
    : selectedIds[0] || undefined;
  const searchReady = searchText.trim().length >= minSearchLength;

  return (
    <Select
      id={id}
      mode={multiple ? 'multiple' : undefined}
      value={selectValue}
      options={visibleOptions}
      style={{width: '100%'}}
      showSearch
      allowClear={!required}
      maxTagCount="responsive"
      disabled={disabled || readonly}
      loading={loading}
      status={rawErrors && rawErrors.length > 0 ? 'error' : undefined}
      placeholder={placeholder || t('form.userPicker.placeholder', '输入邮箱或手机号搜索用户')}
      filterOption={false}
      onSearch={(keyword) => {
        setSearchText(keyword);
        if (debounceTimer.current) clearTimeout(debounceTimer.current);
        if (keyword.trim().length < minSearchLength) {
          listRequestSequence.current += 1;
          setRemoteOptions([]);
          setActiveKeyword('');
          setLoadedPage(0);
          setTotalPages(0);
          setLoading(false);
          setLoadError(false);
          return;
        }
        debounceTimer.current = setTimeout(() => void requestPage(keyword, 0, false), debounceMs);
      }}
      onPopupScroll={(event) => {
        const target = event.currentTarget;
        const nearBottom = target.scrollTop + target.clientHeight >= target.scrollHeight - 24;
        if (nearBottom && !loading && loadedPage + 1 < totalPages) {
          void requestPage(activeKeyword, loadedPage + 1, true);
        }
      }}
      onChange={(nextValue) => {
        const nextIds = normalizeIds(nextValue, multiple);
        setSelectedOptions(nextIds.map((selectedId) => (
          visibleOptions.find((option) => option.value === selectedId)
            ?? {value: selectedId, label: selectedId}
        )));
        onChange(multiple ? nextIds : nextIds[0]);
      }}
      onBlur={() => onBlur?.(id, value)}
      onFocus={() => onFocus?.(id, value)}
      notFoundContent={loading
        ? <Spin size="small" />
        : loadError
          ? t('form.userPicker.loadFailed', '搜索失败，请重新输入后重试')
          : !searchReady
            ? (
              <Typography.Text type="secondary">
                {t('form.userPicker.minSearch', '请输入至少 {count} 位邮箱或手机号', {count: minSearchLength})}
              </Typography.Text>
            )
            : t('form.userPicker.empty', '没有匹配的用户')}
    />
  );
};

export default UserPicker;
