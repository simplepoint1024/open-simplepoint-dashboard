import type {WidgetProps} from '@rjsf/utils';
import {Select, Spin, Typography} from 'antd';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';

type RemoteItem = Record<string, unknown>;

type RemoteOption = {
  label: string;
  value: string | number;
};

type RemoteSelectConfig = {
  endpoint?: unknown;
  pageSize?: unknown;
  debounceMs?: unknown;
  pageParam?: unknown;
  sizeParam?: unknown;
  searchParam?: unknown;
  valueField?: unknown;
  labelField?: unknown;
  secondaryFields?: unknown;
  staticParams?: unknown;
};

const DEFAULT_PAGE_SIZE = 20;
const DEFAULT_DEBOUNCE_MS = 300;

function positiveInteger(value: unknown, fallback: number) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function fieldName(value: unknown, fallback: string) {
  return typeof value === 'string' && value.trim() ? value.trim() : fallback;
}

function stringArray(value: unknown, fallback: string[]) {
  if (!Array.isArray(value)) return fallback;
  const result = value.filter((item): item is string => typeof item === 'string' && !!item.trim());
  return result.length > 0 ? result : fallback;
}

function plainRecord(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  return Object.fromEntries(
    Object.entries(value)
      .filter(([, item]) => ['string', 'number', 'boolean'].includes(typeof item))
      .map(([key, item]) => [key, String(item)]),
  );
}

function optionKey(value: unknown) {
  return value == null ? '' : String(value);
}

function mergeOptions(...groups: RemoteOption[][]) {
  const result = new Map<string, RemoteOption>();
  groups.flat().forEach((option) => result.set(optionKey(option.value), option));
  return [...result.values()];
}

const RemoteSelect = (props: WidgetProps) => {
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
  } = props;
  const {t} = useI18n();
  const config = (options ?? {}) as RemoteSelectConfig;
  const endpoint = typeof config.endpoint === 'string' && config.endpoint.startsWith('/')
    ? config.endpoint
    : '';
  const pageSize = positiveInteger(config.pageSize, DEFAULT_PAGE_SIZE);
  const debounceMs = positiveInteger(config.debounceMs, DEFAULT_DEBOUNCE_MS);
  const pageParam = fieldName(config.pageParam, 'page');
  const sizeParam = fieldName(config.sizeParam, 'size');
  const searchParam = fieldName(config.searchParam, 'keyword');
  const valueField = fieldName(config.valueField, 'id');
  const labelField = fieldName(config.labelField, 'name');
  const secondaryFields = useMemo(
    () => stringArray(config.secondaryFields, ['email', 'phoneNumber']),
    [config.secondaryFields],
  );
  const staticParams = useMemo(() => plainRecord(config.staticParams), [config.staticParams]);
  const configKey = useMemo(
    () => JSON.stringify({endpoint, pageSize, pageParam, sizeParam, searchParam, valueField, labelField, secondaryFields, staticParams}),
    [endpoint, labelField, pageParam, pageSize, searchParam, secondaryFields, sizeParam, staticParams, valueField],
  );

  const [remoteOptions, setRemoteOptions] = useState<RemoteOption[]>([]);
  const [selectedOption, setSelectedOption] = useState<RemoteOption>();
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [loadedPage, setLoadedPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [activeKeyword, setActiveKeyword] = useState('');
  const listRequestSequence = useRef(0);
  const lookupRequestSequence = useRef(0);
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const toOption = useCallback((item: RemoteItem): RemoteOption | undefined => {
    const rawValue = item[valueField];
    if (typeof rawValue !== 'string' && typeof rawValue !== 'number') return undefined;
    const rawLabel = item[labelField];
    const primary = rawLabel == null || String(rawLabel).trim() === '' ? String(rawValue) : String(rawLabel);
    const secondary = secondaryFields
      .map((field) => item[field])
      .find((candidate) => candidate != null && String(candidate).trim() !== '');
    return {
      value: rawValue,
      label: secondary == null ? primary : `${primary} (${String(secondary)})`,
    };
  }, [labelField, secondaryFields, valueField]);

  const requestPage = useCallback(async (keyword: string, page: number, append: boolean) => {
    if (!endpoint) return;
    const requestSequence = ++listRequestSequence.current;
    setLoading(true);
    setLoadError(false);
    try {
      const params: Record<string, string> = {
        ...staticParams,
        [pageParam]: String(page),
        [sizeParam]: String(pageSize),
      };
      if (keyword.trim()) params[searchParam] = keyword.trim();
      const rawPage = await get<RawPage<RemoteItem>>(endpoint, params);
      if (requestSequence !== listRequestSequence.current) return;
      const result = normalizePage(rawPage, pageSize);
      const nextOptions = result.content
        .map(toOption)
        .filter((item): item is RemoteOption => !!item);
      setRemoteOptions((current) => append ? mergeOptions(current, nextOptions) : nextOptions);
      setLoadedPage(result.page.number);
      setTotalPages(result.page.totalPages);
      setActiveKeyword(keyword);
    } catch {
      if (requestSequence === listRequestSequence.current) setLoadError(true);
    } finally {
      if (requestSequence === listRequestSequence.current) setLoading(false);
    }
  }, [endpoint, pageParam, pageSize, searchParam, sizeParam, staticParams, toOption]);

  useEffect(() => {
    setRemoteOptions([]);
    setLoadedPage(0);
    setTotalPages(0);
    setActiveKeyword('');
    void requestPage('', 0, false);
    return () => {
      listRequestSequence.current += 1;
    };
  }, [configKey, requestPage]);

  useEffect(() => {
    const selectedKey = optionKey(value);
    if (!selectedKey || !endpoint) {
      setSelectedOption(undefined);
      return;
    }
    const known = remoteOptions.find((option) => optionKey(option.value) === selectedKey);
    if (known) {
      setSelectedOption(known);
      return;
    }
    const requestSequence = ++lookupRequestSequence.current;
    const resolveSelected = async () => {
      try {
        const rawPage = await get<RawPage<RemoteItem>>(endpoint, {
          ...staticParams,
          [pageParam]: '0',
          [sizeParam]: String(Math.min(pageSize, 20)),
          [searchParam]: selectedKey,
        });
        if (requestSequence !== lookupRequestSequence.current) return;
        const matched = normalizePage(rawPage, pageSize).content
          .map(toOption)
          .find((option) => optionKey(option?.value) === selectedKey);
        setSelectedOption(matched ?? {value: value as string | number, label: selectedKey});
      } catch {
        if (requestSequence === lookupRequestSequence.current) {
          setSelectedOption({value: value as string | number, label: selectedKey});
        }
      }
    };
    void resolveSelected();
    return () => {
      lookupRequestSequence.current += 1;
    };
  }, [configKey, endpoint, pageParam, pageSize, remoteOptions, searchParam, sizeParam, staticParams, toOption, value]);

  useEffect(() => () => {
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
  }, []);

  const visibleOptions = useMemo(
    () => selectedOption ? mergeOptions([selectedOption], remoteOptions) : remoteOptions,
    [remoteOptions, selectedOption],
  );

  if (!endpoint) {
    return (
      <Typography.Text type="danger">
        {t('form.remoteSelect.invalidEndpoint', '远程选择器未配置有效的数据地址')}
      </Typography.Text>
    );
  }

  return (
    <Select
      id={id}
      value={value == null || value === '' ? undefined : value}
      options={visibleOptions}
      style={{width: '100%'}}
      showSearch
      allowClear={!required}
      disabled={disabled || readonly}
      loading={loading}
      status={rawErrors && rawErrors.length > 0 ? 'error' : undefined}
      placeholder={placeholder || t('form.remoteSelect.placeholder', '输入名称、账号、邮箱或手机号搜索')}
      filterOption={false}
      onSearch={(keyword) => {
        if (debounceTimer.current) clearTimeout(debounceTimer.current);
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
        const matched = visibleOptions.find((option) => optionKey(option.value) === optionKey(nextValue));
        setSelectedOption(matched);
        onChange(nextValue ?? undefined);
      }}
      onBlur={() => onBlur?.(id, value)}
      onFocus={() => onFocus?.(id, value)}
      notFoundContent={loading
        ? <Spin size="small" />
        : loadError
          ? t('form.remoteSelect.loadFailed', '加载失败，请重新输入关键词重试')
          : t('form.remoteSelect.empty', '没有匹配的数据')}
    />
  );
};

export default RemoteSelect;
