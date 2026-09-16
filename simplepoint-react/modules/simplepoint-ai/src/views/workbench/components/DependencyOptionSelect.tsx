import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {
  Alert,
  Button,
  Select,
  Space,
  Spin,
  Typography,
  type SelectProps,
} from 'antd';
import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type CSSProperties,
  type ReactNode,
} from 'react';
import {
  chunkDependencyIds,
  collectDependencySelectionIssues,
  dependencyOptionValue,
  dependencySelectionKey,
  isDependencyAbortError,
  mergeDependencyOptions,
  normalizeDependencyPage,
  normalizeDependencyQuery,
  uniqueDependencyIds,
  type DependencyOption,
  type DependencyOptionKind,
  type DependencyPage,
  type DependencyResolution,
  type DependencySelectionIssue,
} from './dependencyOptions';

const {Text} = Typography;
const PAGE_SIZE = 20;

type QueryEntry = {
  items: DependencyOption[];
  page: number;
  totalElements: number;
  totalPages: number;
  loaded: boolean;
  loading: boolean;
  loadingMore: boolean;
  error?: unknown;
  failedPage?: number;
  requestSequence: number;
  subscribers: number;
  controller?: AbortController;
};

type DirectoryStore = {
  active: boolean;
  queries: Map<string, QueryEntry>;
  options: Map<string, DependencyOption>;
  resolutions: Map<string, DependencyResolution>;
  resolutionSequences: Map<string, number>;
  controllers: Set<AbortController>;
};

type ResolveOptions = {
  force?: boolean;
};

export type DependencyQuerySnapshot = {
  items: DependencyOption[];
  page: number;
  totalElements: number;
  totalPages: number;
  loaded: boolean;
  loading: boolean;
  loadingMore: boolean;
  error?: unknown;
  hasMore: boolean;
};

export type DependencyOptionDirectory = {
  enabled: boolean;
  revision: number;
  search: (
    kind: DependencyOptionKind,
    query?: string,
    force?: boolean,
  ) => Promise<void>;
  loadMore: (kind: DependencyOptionKind, query?: string) => Promise<void>;
  retry: (kind: DependencyOptionKind, query?: string) => Promise<void>;
  activateQuery: (kind: DependencyOptionKind, query?: string) => () => void;
  prefetch: (kinds: readonly DependencyOptionKind[]) => Promise<void>;
  resolve: (
    kind: DependencyOptionKind,
    ids: readonly (string | null | undefined)[],
    options?: ResolveOptions,
  ) => Promise<DependencySelectionIssue[]>;
  validate: (
    kind: DependencyOptionKind,
    ids: readonly (string | null | undefined)[],
    refresh?: boolean,
  ) => Promise<DependencySelectionIssue[]>;
  getQuery: (
    kind: DependencyOptionKind,
    query?: string,
  ) => DependencyQuerySnapshot;
  getOption: (
    kind: DependencyOptionKind,
    id: string,
  ) => DependencyOption | undefined;
  getResolution: (
    kind: DependencyOptionKind,
    id: string,
  ) => DependencyResolution | undefined;
};

type DirectoryConfig = {
  baseUrl: string;
  consumerId?: string;
  contextKey: string | number;
  enabled: boolean;
};

const createStore = (): DirectoryStore => ({
  active: true,
  queries: new Map(),
  options: new Map(),
  resolutions: new Map(),
  resolutionSequences: new Map(),
  controllers: new Set(),
});

const queryKey = (kind: DependencyOptionKind, query?: string) =>
  `${kind}:${normalizeDependencyQuery(query)}`;

const emptyQuerySnapshot = (): DependencyQuerySnapshot => ({
  items: [],
  page: -1,
  totalElements: 0,
  totalPages: 0,
  loaded: false,
  loading: false,
  loadingMore: false,
  hasMore: false,
});

const ensureQueryEntry = (
  store: DirectoryStore,
  kind: DependencyOptionKind,
  query?: string,
) => {
  const key = queryKey(kind, query);
  let entry = store.queries.get(key);
  if (!entry) {
    entry = {
      items: [],
      page: -1,
      totalElements: 0,
      totalPages: 0,
      loaded: false,
      loading: false,
      loadingMore: false,
      requestSequence: 0,
      subscribers: 0,
    };
    store.queries.set(key, entry);
  }
  return entry;
};

const optionMatchesKind = (
  option: DependencyOption,
  kind: DependencyOptionKind,
) => option.kind === kind && Boolean(dependencyOptionValue(option));

export const useDependencyOptionDirectory = ({
  baseUrl,
  consumerId,
  contextKey,
  enabled,
}: DirectoryConfig): DependencyOptionDirectory => {
  const identity = `${baseUrl}:${consumerId ?? ''}:${contextKey}:${enabled}`;
  const store = useMemo(createStore, [identity]);
  const [revision, setRevision] = useState(0);
  const endpoint = consumerId
    ? `${baseUrl}/${consumerId}/dependency-options`
    : undefined;

  const notify = useCallback(() => {
    if (store.active) setRevision((current) => current + 1);
  }, [store]);

  useEffect(() => () => {
    store.active = false;
    store.controllers.forEach((controller) => controller.abort());
    store.controllers.clear();
  }, [store]);

  const loadPage = useCallback(async (
    kind: DependencyOptionKind,
    query: string,
    page: number,
    force = false,
  ) => {
    if (!enabled || !endpoint || !store.active) return;
    const normalizedQuery = normalizeDependencyQuery(query);
    const entry = ensureQueryEntry(store, kind, normalizedQuery);
    if (!force) {
      if (page === 0 && (entry.loading || (entry.loaded && !entry.error))) return;
      if (page > 0 && entry.loadingMore) return;
    }
    entry.controller?.abort();
    const controller = new AbortController();
    entry.controller = controller;
    store.controllers.add(controller);
    const requestSequence = ++entry.requestSequence;
    entry.error = undefined;
    entry.failedPage = undefined;
    if (page === 0) entry.loading = true;
    else entry.loadingMore = true;
    notify();
    try {
      const raw = await get<DependencyPage>(
        endpoint,
        {
          kind,
          q: normalizedQuery,
          page,
          size: PAGE_SIZE,
        },
        {signal: controller.signal, notifyOnError: false},
      );
      if (!store.active || requestSequence !== entry.requestSequence) return;
      const result = normalizeDependencyPage(raw, PAGE_SIZE);
      const incoming = result.content.filter((option) =>
        optionMatchesKind(option, kind));
      incoming.forEach((option) => {
        const id = dependencyOptionValue(option);
        const key = dependencySelectionKey(kind, id);
        store.options.set(key, option);
        store.resolutions.set(key, {status: 'resolved'});
      });
      entry.items = page === 0
        ? incoming
        : mergeDependencyOptions(entry.items, incoming);
      entry.page = result.page.number;
      entry.totalElements = result.page.totalElements;
      entry.totalPages = result.page.totalPages;
      entry.loaded = true;
    } catch (error) {
      if (!store.active || requestSequence !== entry.requestSequence
        || isDependencyAbortError(error)) return;
      entry.error = error;
      entry.failedPage = page;
    } finally {
      store.controllers.delete(controller);
      if (store.active && requestSequence === entry.requestSequence) {
        if (entry.controller === controller) entry.controller = undefined;
        entry.loading = false;
        entry.loadingMore = false;
        notify();
      }
    }
  }, [enabled, endpoint, notify, store]);

  const search = useCallback(async (
    kind: DependencyOptionKind,
    query = '',
    force = false,
  ) => loadPage(kind, normalizeDependencyQuery(query), 0, force), [loadPage]);

  const loadMore = useCallback(async (
    kind: DependencyOptionKind,
    query = '',
  ) => {
    const normalizedQuery = normalizeDependencyQuery(query);
    const entry = ensureQueryEntry(store, kind, normalizedQuery);
    if (!entry.loaded || entry.loading || entry.loadingMore
      || entry.page + 1 >= entry.totalPages) return;
    await loadPage(kind, normalizedQuery, entry.page + 1);
  }, [loadPage, store]);

  const retry = useCallback(async (
    kind: DependencyOptionKind,
    query = '',
  ) => {
    const normalizedQuery = normalizeDependencyQuery(query);
    const entry = ensureQueryEntry(store, kind, normalizedQuery);
    await loadPage(kind, normalizedQuery, entry.failedPage ?? 0, true);
  }, [loadPage, store]);

  const activateQuery = useCallback((
    kind: DependencyOptionKind,
    query = '',
  ) => {
    const entry = ensureQueryEntry(store, kind, query);
    entry.subscribers += 1;
    let active = true;
    return () => {
      if (!active) return;
      active = false;
      entry.subscribers = Math.max(0, entry.subscribers - 1);
      if (entry.subscribers === 0 && (entry.loading || entry.loadingMore)) {
        entry.controller?.abort();
      }
    };
  }, [store]);

  const prefetch = useCallback(async (
    kinds: readonly DependencyOptionKind[],
  ) => {
    await Promise.allSettled(
      Array.from(new Set(kinds)).map((kind) => search(kind)),
    );
  }, [search]);

  const resolve = useCallback(async (
    kind: DependencyOptionKind,
    ids: readonly (string | null | undefined)[],
    options?: ResolveOptions,
  ) => {
    const requested = uniqueDependencyIds(ids);
    if (!enabled || !endpoint || !store.active || requested.length === 0) {
      return collectDependencySelectionIssues(
        kind,
        requested,
        store.options,
        store.resolutions,
      );
    }
    const targets = requested.filter((id) => {
      if (options?.force) return true;
      const key = dependencySelectionKey(kind, id);
      const resolution = store.resolutions.get(key);
      return !store.options.has(key)
        && resolution?.status !== 'loading'
        && resolution?.status !== 'unresolved';
    });
    if (targets.length === 0) {
      return collectDependencySelectionIssues(
        kind,
        requested,
        store.options,
        store.resolutions,
      );
    }

    const sequences = new Map<string, number>();
    targets.forEach((id) => {
      const key = dependencySelectionKey(kind, id);
      const sequence = (store.resolutionSequences.get(key) ?? 0) + 1;
      store.resolutionSequences.set(key, sequence);
      sequences.set(key, sequence);
      store.resolutions.set(key, {status: 'loading'});
    });
    notify();

    await Promise.all(chunkDependencyIds(targets).map(async (chunk) => {
      const controller = new AbortController();
      store.controllers.add(controller);
      try {
        const result = await get<DependencyOption[]>(
          `${endpoint}/resolve`,
          {kind, ids: chunk.join(',')},
          {signal: controller.signal, notifyOnError: false},
        );
        if (!store.active) return;
        const resolved = new Map(
          (Array.isArray(result) ? result : [])
            .filter((option) => optionMatchesKind(option, kind))
            .map((option) => [dependencyOptionValue(option), option]),
        );
        chunk.forEach((id) => {
          const key = dependencySelectionKey(kind, id);
          if (store.resolutionSequences.get(key) !== sequences.get(key)) return;
          const option = resolved.get(id);
          if (option) {
            store.options.set(key, option);
            store.resolutions.set(key, {status: 'resolved'});
            return;
          }
          const existing = store.options.get(key);
          if (existing) {
            store.options.set(key, {
              ...existing,
              selectable: false,
              availabilityCode: 'UNRESOLVED',
            });
          }
          store.resolutions.set(key, {status: 'unresolved'});
        });
      } catch (error) {
        if (!store.active || isDependencyAbortError(error)) return;
        chunk.forEach((id) => {
          const key = dependencySelectionKey(kind, id);
          if (store.resolutionSequences.get(key) === sequences.get(key)) {
            store.resolutions.set(key, {status: 'error', error});
          }
        });
      } finally {
        store.controllers.delete(controller);
        if (store.active) notify();
      }
    }));
    return collectDependencySelectionIssues(
      kind,
      requested,
      store.options,
      store.resolutions,
    );
  }, [enabled, endpoint, notify, store]);

  const validate = useCallback(async (
    kind: DependencyOptionKind,
    ids: readonly (string | null | undefined)[],
    refresh = true,
  ) => resolve(kind, ids, {force: refresh}), [resolve]);

  const getQuery = useCallback((
    kind: DependencyOptionKind,
    query = '',
  ): DependencyQuerySnapshot => {
    const entry = store.queries.get(queryKey(kind, query));
    if (!entry) return emptyQuerySnapshot();
    return {
      items: entry.items,
      page: entry.page,
      totalElements: entry.totalElements,
      totalPages: entry.totalPages,
      loaded: entry.loaded,
      loading: entry.loading,
      loadingMore: entry.loadingMore,
      error: entry.error,
      hasMore: entry.loaded && entry.page + 1 < entry.totalPages,
    };
  }, [store]);

  const getOption = useCallback((kind: DependencyOptionKind, id: string) =>
    store.options.get(dependencySelectionKey(kind, id)), [store]);

  const getResolution = useCallback((kind: DependencyOptionKind, id: string) =>
    store.resolutions.get(dependencySelectionKey(kind, id)), [store]);

  return {
    enabled,
    revision,
    search,
    loadMore,
    retry,
    activateQuery,
    prefetch,
    resolve,
    validate,
    getQuery,
    getOption,
    getResolution,
  };
};

type CommonProps = {
  directory: DependencyOptionDirectory;
  kind: DependencyOptionKind;
  placeholder?: ReactNode;
  disabled?: boolean;
  allowClear?: boolean;
  style?: CSSProperties;
  className?: string;
  status?: SelectProps['status'];
  excludeValues?: readonly string[];
};

type SingleProps = CommonProps & {
  mode?: undefined;
  value?: string;
  onChange?: (value?: string, option?: DependencyOption) => void;
};

type MultipleProps = CommonProps & {
  mode: 'multiple';
  value?: string[];
  maxCount?: number;
  onChange?: (value: string[], options: DependencyOption[]) => void;
};

export type DependencyOptionSelectProps = SingleProps | MultipleProps;

type Translate = (
  key: string,
  fallback?: string,
  params?: Record<string, unknown>,
) => string;

const formatOptionLabel = (option: DependencyOption) => {
  const name = option.resourceName?.trim()
    || option.resourceCode?.trim()
    || dependencyOptionValue(option);
  const code = option.resourceCode?.trim();
  const resource = code && code !== name ? `${name} · ${code}` : name;
  return option.kind === 'MODEL' || !option.resourceVersion
    ? resource
    : `${resource}@${option.resourceVersion}`;
};

const availabilityText = (
  t: Translate,
  code?: string | null,
) => {
  switch (code) {
    case 'DEPENDENCY_DISABLED':
      return t(
        'ai.dependencies.availability.disabled',
        '依赖已停用，请选择其他依赖',
      );
    case 'MODEL_UNAVAILABLE':
      return t(
        'ai.dependencies.availability.modelUnavailable',
        '模型当前不可用，请选择其他模型',
      );
    case 'VERSION_DEPRECATED':
      return t(
        'ai.dependencies.availability.versionDeprecated',
        '版本已废弃，请选择其他已发布版本',
      );
    default:
      return t(
        'ai.dependencies.availability.unresolved',
        '已选依赖未找到或当前作用域不可见，不能继续保存',
      );
  }
};

const shortId = (id: string) => id.length > 18
  ? `${id.slice(0, 8)}…${id.slice(-6)}`
  : id;

const DependencyOptionSelect = (props: DependencyOptionSelectProps) => {
  const {
    directory,
    kind,
    placeholder,
    disabled,
    allowClear,
    style,
    className,
    status,
    excludeValues = [],
  } = props;
  const {t} = useI18n();
  const [searchValue, setSearchValue] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const selectedIds = uniqueDependencyIds(
    Array.isArray(props.value) ? props.value : [props.value],
  );
  const {
    enabled: directoryEnabled,
    activateQuery,
    search,
    resolve,
    getOption,
    getResolution,
  } = directory;

  useEffect(() => {
    const timer = globalThis.setTimeout(() => {
      setDebouncedQuery(normalizeDependencyQuery(searchValue));
    }, 300);
    return () => globalThis.clearTimeout(timer);
  }, [searchValue]);

  useEffect(() => {
    if (!directoryEnabled) return undefined;
    const release = activateQuery(kind, debouncedQuery);
    void search(kind, debouncedQuery);
    return release;
  }, [activateQuery, debouncedQuery, directoryEnabled, kind, search]);

  useEffect(() => {
    const unresolved = selectedIds.filter((id) =>
      !getOption(kind, id)
      && !getResolution(kind, id));
    if (unresolved.length > 0) void resolve(kind, unresolved);
  }, [getOption, getResolution, kind, resolve, selectedIds.join('|')]);

  const query = directory.getQuery(kind, debouncedQuery);
  const selectedOptions = selectedIds.flatMap((id) => {
    const option = getOption(kind, id);
    return option ? [option] : [];
  });
  const excluded = new Set(uniqueDependencyIds(excludeValues));
  const optionById = new Map(
    mergeDependencyOptions(query.items, selectedOptions)
      .filter((option) => selectedIds.includes(dependencyOptionValue(option))
        || !excluded.has(dependencyOptionValue(option)))
      .map((option) => [dependencyOptionValue(option), option]),
  );
  const options = [
    ...Array.from(optionById.values()).map((option) => ({
      value: dependencyOptionValue(option),
      label: option.selectable
        ? formatOptionLabel(option)
        : `${formatOptionLabel(option)} · ${availabilityText(t, option.availabilityCode)}`,
      disabled: !option.selectable,
    })),
    ...selectedIds.filter((id) => !optionById.has(id)).map((id) => {
      const resolution = getResolution(kind, id);
      const suffix = resolution?.status === 'loading'
        ? t('ai.dependencies.resolving', '正在解析已选依赖…')
        : resolution?.status === 'error'
          ? t('ai.dependencies.resolveFailed', '已选依赖加载失败')
          : availabilityText(t);
      return {value: id, label: `${shortId(id)} · ${suffix}`, disabled: true};
    }),
  ];

  const selectedIssue = selectedIds.map((id) => ({
    id,
    option: getOption(kind, id),
    resolution: getResolution(kind, id),
  })).find(({option, resolution}) =>
    resolution?.status === 'loading'
    || resolution?.status === 'error'
    || resolution?.status === 'unresolved'
    || option?.selectable === false);

  const retrySelected = () => {
    if (selectedIssue) {
      void directory.resolve(kind, [selectedIssue.id], {force: true});
    }
  };

  const popupError = query.error ? (
    <Alert
      showIcon
      type="error"
      style={{margin: 8}}
      message={t('ai.dependencies.loadFailed', '依赖选项加载失败')}
      description={resolveApiErrorMessage(
        query.error,
        t('error.requestFailed', '请求失败'),
      )}
      action={(
        <Button
          size="small"
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => void directory.retry(kind, debouncedQuery)}
        >
          {t('action.retry', '重试')}
        </Button>
      )}
    />
  ) : null;

  const loadMore = query.hasMore || query.loadingMore ? (
    <Button
      block
      type="text"
      loading={query.loadingMore}
      onMouseDown={(event) => event.preventDefault()}
      onClick={() => void directory.loadMore(kind, debouncedQuery)}
    >
      {t('ai.dependencies.loadMore', '加载更多')}
    </Button>
  ) : null;

  const select = (
    <Select
      showSearch
      filterOption={false}
      searchValue={searchValue}
      onSearch={(value) => setSearchValue(value.slice(0, 128))}
      onBlur={() => setSearchValue('')}
      mode={props.mode}
      maxCount={props.mode === 'multiple' ? props.maxCount : undefined}
      value={props.value}
      options={options}
      placeholder={placeholder}
      disabled={disabled || !directory.enabled}
      allowClear={allowClear}
      style={style}
      className={className}
      status={selectedIssue && selectedIssue.resolution?.status !== 'loading'
        ? 'error' : status}
      loading={query.loading && !query.loaded}
      notFoundContent={query.error && query.items.length === 0
        ? null
        : query.loading && !query.loaded
        ? <Spin size="small" />
        : <Text type="secondary">
          {debouncedQuery
            ? t('ai.dependencies.noMatch', '没有匹配的依赖，请尝试其他关键词')
            : t('ai.dependencies.empty', '当前作用域没有可用依赖')}
        </Text>}
      popupRender={(menu) => (
        <div>
          {menu}
          {popupError}
          {loadMore}
        </div>
      )}
      onPopupScroll={(event) => {
        const target = event.currentTarget;
        if (target.scrollTop + target.clientHeight >= target.scrollHeight - 24) {
          void directory.loadMore(kind, debouncedQuery);
        }
      }}
      onChange={(value) => {
        if (props.mode === 'multiple') {
          const values = Array.isArray(value) ? value : [];
          props.onChange?.(
            values,
            values.flatMap((id) => {
              const option = getOption(kind, id);
              return option ? [option] : [];
            }),
          );
          return;
        }
        const id = typeof value === 'string' ? value : undefined;
        props.onChange?.(
          id,
          id ? getOption(kind, id) : undefined,
        );
      }}
    />
  );

  if (!selectedIssue) return select;
  const selectedMessage = selectedIssue.resolution?.status === 'loading'
    ? t('ai.dependencies.resolving', '正在解析已选依赖…')
    : selectedIssue.resolution?.status === 'error'
      ? t('ai.dependencies.resolveFailed', '已选依赖加载失败，表单内容已保留')
      : availabilityText(t, selectedIssue.option?.availabilityCode);
  return (
    <Space direction="vertical" size={4} style={{width: '100%'}}>
      {select}
      <Space size={4} wrap>
        <Text type={selectedIssue.resolution?.status === 'loading'
          ? 'secondary' : 'danger'}>
          {selectedMessage}
        </Text>
        {selectedIssue.resolution?.status === 'error' && (
          <Button type="link" size="small" onClick={retrySelected}>
            {t('action.retry', '重试')}
          </Button>
        )}
      </Space>
    </Space>
  );
};

export default DependencyOptionSelect;
