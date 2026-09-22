import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type {QueryFunctionContext} from '@tanstack/react-query';
import type { IChangeEvent } from '@rjsf/core';
import { App } from 'antd';
import { sortSchemaProperties, useSchema } from '@simplepoint/shared/hooks/useSchema';
import { isHttpError, resolveApiErrorMessage } from '@simplepoint/shared/api/client';
import { del, get, post, put, usePage } from '@simplepoint/shared/api/methods';
import { useI18n } from '@simplepoint/shared/hooks/useI18n';
import { getStoredContextId, getStoredRoleId, getStoredTenantId } from '@simplepoint/shared/api/contextId';
import type { TableButtonProps } from '../Table';
import type { SimpleTableAfterSubmitContext, SimpleTableBeforeSubmitContext, SimpleTableProps, SimpleTableRefreshTargets, SimpleTableSubmitAction } from './types';
import {resolveSimpleTableOperationError} from './errorMessage';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {scopedPagePlaceholder} from '@simplepoint/shared/api/queryScope';
import type {Page} from '@simplepoint/shared/types/request';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {transportFilters} from '../Table/query';
import {resolveSimpleTableLoadError} from './errorMessage';

const nsLoadedCache = new Set<string>();

export type SimpleTableController<T> = {
  t: ReturnType<typeof useI18n>['t'];
  status: {
    bootLoading: boolean;
    tableLoading: boolean;
    submitLoading: boolean;
    submitError?: string;
    drawerSchemaLoading: boolean;
    hasSchemaError: boolean;
    hasPageError: boolean;
    showPageWarning: boolean;
    pageErrorMessage: string;
  };
  schema: {
    data: any;
    formSchema: any;
    error: unknown;
    dictionariesError: unknown;
    retryDictionaries: () => Promise<unknown>;
    retry: () => Promise<unknown>;
  };
  table: {
    data: any;
    filters: Record<string, string>;
    sorter: string | string[] | undefined;
    onQueryChange: (filters: Record<string, string>, sort?: string | string[]) => void;
    buttons: TableButtonProps[];
    onChange: (pagination: any, _tableFilters: any, sorter?: any) => void;
    onFilterChange: (filters: Record<string, string>) => void;
    refresh: () => void;
    refreshDisabled: boolean;
  };
  drawer: {
    open: boolean;
    editingRecord: any | null;
    setOpen: (open: boolean) => void;
    close: () => void;
  };
  actions: {
    defaultEvents: Record<string, (selectedRowKeys: React.Key[], selectedRows: T[], props?: TableButtonProps) => void>;
    handleFormSubmit: (event: IChangeEvent) => Promise<void>;
    retryPage: () => Promise<unknown>;
  };
};

export function useSimpleTableController<T = any>(props: SimpleTableProps<T>): SimpleTableController<T> {
  const scope = useQueryScope();
  const { t, ensure, locale, ready } = useI18n();
  const {message, modal} = App.useApp();
  const [i18nReady, setI18nReady] = useState(false);
  const [page, setPage] = useState<number>(1);
  const [size, setSize] = useState<number>(20);
  const [filters, setFilters] = useState<Record<string, string>>(props.initialFilters ?? {});
  const [sort, setSort] = useState<string | string[] | undefined>(undefined);
  const [tenantId, setTenantId] = useState<string>(() => getStoredTenantId() ?? '');
  const [roleId, setRoleId] = useState<string>(() => getStoredRoleId(getStoredTenantId()) ?? '');
  const [contextId, setContextId] = useState<string>(() => getStoredContextId(getStoredTenantId(), getStoredRoleId(getStoredTenantId())) ?? '');
  const [innerDrawerOpen, setInnerDrawerOpen] = useState(false);
  const [innerEditing, setInnerEditing] = useState<any | null>(null);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [submitError, setSubmitError] = useState<string>();
  useEffect(() => {setSubmitError(undefined);}, [props.drawerOpen]);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const submissionLock = useRef(false);

  const submitRefreshTargets = useMemo<Required<SimpleTableRefreshTargets>>(
    () => ({
      page: props.submitRefreshTargets?.page ?? true,
      schema: props.submitRefreshTargets?.schema ?? true,
    }),
    [props.submitRefreshTargets]
  );

  const deleteRefreshTargets = useMemo<Required<SimpleTableRefreshTargets>>(
    () => ({
      page: props.deleteRefreshTargets?.page ?? true,
      schema: props.deleteRefreshTargets?.schema ?? true,
    }),
    [props.deleteRefreshTargets]
  );

  useEffect(() => {
    const ns = Array.isArray(props.i18nNamespaces) ? props.i18nNamespaces : [];
    const merged = Array.from(new Set(['table', ...ns])).sort();
    const cacheKey = `${locale}::${merged.join(',')}`;
    if (nsLoadedCache.has(cacheKey)) {
      setI18nReady(true);
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        await ensure(merged as string[]);
      } finally {
        if (!cancelled) {
          nsLoadedCache.add(cacheKey);
          setI18nReady(true);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [props.i18nNamespaces, ensure, locale]);

  const { data: schemaData, isLoading: schemaLoading, error: schemaError, refetch: refetchSchema,
    dictionariesLoading, dictionariesError, retryDictionaries } = useSchema(props.baseUrl, {staleTime: props.schemaStaleTime});

  const drawerOpen = props.drawerOpen ?? innerDrawerOpen;
  // Custom routes remain protected unless their owner explicitly opts in.
  usePageRetention(props.allowTabEviction === true && !drawerOpen && !submitLoading && !confirmingDelete,
    drawerOpen || submitLoading || confirmingDelete);
  const setDrawerOpen = (open: boolean) => {
    props.onDrawerOpenChange?.(open);
    if (props.drawerOpen === undefined) setInnerDrawerOpen(open);
  };

  const editingRecord = props.editingRecord ?? innerEditing;
  const setEditingRecord = (rec: any | null) => {
    props.onEditingRecordChange?.(rec);
    if (props.editingRecord === undefined) setInnerEditing(rec);
  };

  const fetchPage = ({signal}: QueryFunctionContext) =>
    props.loadPage
      ? props.loadPage({page: page - 1, size, filters: transportFilters(filters), sort, signal})
      : get<import('@simplepoint/shared/types/request').Page<any>>(props.baseUrl, {
          page: page - 1,
          size,
          ...transportFilters(filters),
          ...(sort ? {sort} : {}),
        }, {signal});

  const { data: pageData, isLoading: pageLoading, isFetching: pageFetching, error: pageError, refetch: refetchPage } = usePage(
    [props.baseUrl, props.name, props.queryContext ?? '', tenantId, roleId, contextId, page, size, filters, sort],
    fetchPage,
    {
      placeholderData: scopedPagePlaceholder<Page<any>>(scope, [props.baseUrl, props.name, props.queryContext ?? '']),
      retry: (count, error) => !(isHttpError(error) && error.status >= 400 && error.status < 500)
        && !(isHttpError(error) && error.status === 500) && count < 1,
    }
  );

  useEffect(() => {
    const handleTenantChange = (event: Event) => {
      const nextTenantId = (event as CustomEvent<string | undefined>).detail ?? getStoredTenantId() ?? '';
      const nextRoleId = getStoredRoleId(nextTenantId) ?? '';
      setTenantId(nextTenantId);
      setRoleId(nextRoleId);
      setContextId(getStoredContextId(nextTenantId, nextRoleId) ?? '');
    };

    const handleRoleChange = (event: Event) => {
      const detail = (event as CustomEvent<{ tenantId?: string; roleId?: string }>).detail;
      const nextTenantId = detail?.tenantId ?? tenantId;
      if ((nextTenantId ?? '') !== tenantId) {
        return;
      }
      const nextRoleId = detail?.roleId ?? getStoredRoleId(nextTenantId) ?? '';
      setRoleId(nextRoleId);
      setContextId(getStoredContextId(nextTenantId, nextRoleId) ?? '');
    };

    const handleContextChange = (event: Event) => {
      const detail = (event as CustomEvent<{ tenantId?: string; roleId?: string; contextId?: string }>).detail;
      if (detail && typeof detail === 'object') {
        if ((detail.tenantId ?? '') !== tenantId) {
          return;
        }
        if ((detail.roleId ?? '') !== roleId) {
          return;
        }
        setContextId(detail.contextId ?? '');
        return;
      }
      setContextId(getStoredContextId(tenantId, roleId) ?? '');
    };

    window.addEventListener('sp-set-tenant', handleTenantChange as EventListener);
    window.addEventListener('sp-set-role', handleRoleChange as EventListener);
    window.addEventListener('sp-set-context-id', handleContextChange as EventListener);

    return () => {
      window.removeEventListener('sp-set-tenant', handleTenantChange as EventListener);
      window.removeEventListener('sp-set-role', handleRoleChange as EventListener);
      window.removeEventListener('sp-set-context-id', handleContextChange as EventListener);
    };
  }, [tenantId, roleId]);

  useEffect(() => {
    setPage(1);
  }, [tenantId, roleId, contextId]);

  const bootLoading = !ready || !i18nReady || (schemaLoading && !schemaData) || (!pageData && pageLoading && !pageError);
  const tableLoading = !bootLoading && pageFetching;
  const drawerSchemaLoading = drawerOpen && ((schemaLoading && !schemaData) || dictionariesLoading);
  const hasSchemaError = !bootLoading && !!schemaError;
  const hasPageError = !bootLoading && !schemaError && !!pageError && !pageData;
  const showPageWarning = !bootLoading && !schemaError && !!pageError && !!pageData;

  const refreshTargets = async (targets: Required<SimpleTableRefreshTargets>) => {
    const tasks: Array<Promise<unknown>> = [];
    if (targets.page) {
      tasks.push(refetchPage());
    }
    if (targets.schema) {
      tasks.push(refetchSchema());
    }
    if (tasks.length === 0) {
      return;
    }
    await Promise.allSettled(tasks);
  };

  const handleTableChange = (pagination: any, _tableFilters: any, sorter: any, extra?: {action?: string}) => {
    setPage(extra?.action === 'sort' || pagination?.pageSize !== size ? 1 : pagination?.current ?? 1);
    setSize(pagination?.pageSize ?? size);
    const list = (Array.isArray(sorter) ? sorter : [sorter]).filter(s => s?.field && s?.order);
    if (list.length > 3) {message.warning(t('table.query.maxSort', '最多支持三个排序字段')); return;}
    const sorts = list.map(s => `${Array.isArray(s.field) ? s.field.join('.') : s.field},${s.order === 'ascend' ? 'asc' : 'desc'}`);
    setSort(sorts.length > 1 ? sorts : sorts[0]);
  };

  const handleFilterChange = useCallback((nextFilters: Record<string, string>) => {
    setFilters(nextFilters);
    setPage(1);
  }, []);
  const refreshPage = useCallback(() => {void refetchPage();}, [refetchPage]);

  const handleAdd = () => {
    setEditingRecord(null);
    setDrawerOpen(true);
  };

  const handleEdit = (_keys: React.Key[], rows: any[]) => {
    setEditingRecord(rows?.[0] ?? null);
    setDrawerOpen(true);
  };

  const handleDelete = (keys: React.Key[]) => {
    setConfirmingDelete(true);
    modal.confirm({
      afterClose: () => setConfirmingDelete(false),
      title: t('table.confirmDeleteTitle', '确认删除'),
      content: t('table.confirmDeleteContent', '确定要删除选中的 {count} 条数据吗？', { count: keys.length }),
      onOk: async () => {
        if (submissionLock.current) return;
        submissionLock.current = true;
        setSubmitLoading(true);
        try {
          await del(props.baseUrl, keys as any);
          message.success(t('table.deleteSuccess', '删除成功'));
          await refreshTargets(deleteRefreshTargets);
        } catch (e: any) {
          if (isHttpError(e) && e.status === 401) {
            return;
          }
          const detail = resolveSimpleTableOperationError(
            e,
            'delete',
            props.errorMessageResolver,
            () => resolveApiErrorMessage(e, ''),
          );
          message.error(t('table.deleteFail', '删除失败: {msg}', {msg: detail}));
        } finally {
          submissionLock.current = false;
          setSubmitLoading(false);
        }
      },
    });
  };

  const handleFormSubmit = async ({ formData }: IChangeEvent) => {
    setSubmitError(undefined);
    if (submissionLock.current) return;
    submissionLock.current = true;
    setSubmitLoading(true);
    try {
      const action: SimpleTableSubmitAction = editingRecord ? 'edit' : 'add';
      const beforeSubmitContext: SimpleTableBeforeSubmitContext = {
        action,
        formData,
        currentEditing: editingRecord,
        baseUrl: props.baseUrl,
      };
      const normalizedFormData = props.beforeSubmit
        ? await props.beforeSubmit(beforeSubmitContext)
        : formData;
      const submittedData = normalizedFormData ?? formData;

      let result: unknown;
      if (props.onSubmit) {
        result = await props.onSubmit(action, submittedData, editingRecord);
      } else if (action === 'edit') {
        result = await put(props.baseUrl, { ...editingRecord, ...submittedData });
        message.success(t('table.editSuccess', '修改成功'));
      } else {
        result = await post(props.baseUrl, submittedData);
        message.success(t('table.addSuccess', '新增成功'));
      }

      const afterSubmitContext: SimpleTableAfterSubmitContext = {
        ...beforeSubmitContext,
        submittedData,
        result,
      };
      if (props.afterSubmit) {
        try {
          await props.afterSubmit(afterSubmitContext);
        } catch (afterSubmitError) {
          console.warn('[SimpleTable] afterSubmit hook failed:', afterSubmitError);
        }
      }

      setDrawerOpen(false);
      setEditingRecord(null);
      await refreshTargets(submitRefreshTargets);
    } catch (e: any) {
      if (isHttpError(e) && e.status === 401) {
        return;
      }
      const action: SimpleTableSubmitAction = editingRecord ? 'edit' : 'add';
      const detail = resolveSimpleTableOperationError(
        e,
        action,
        props.errorMessageResolver,
        () => resolveApiErrorMessage(e, ''),
      );
      setSubmitError(detail);
      message.error(t('table.actionFail', '操作失败: {msg}', {msg: detail}));
    } finally {
      submissionLock.current = false;
      setSubmitLoading(false);
    }
  };

  const mergedButtons: TableButtonProps[] = useMemo(() => {
    const arr = [...(schemaData?.buttons ?? []), ...(props.customButtons ?? [])];
    return arr.sort((a, b) => {
      const s1 = typeof a.sort === 'number' ? a.sort : Infinity;
      const s2 = typeof b.sort === 'number' ? b.sort : Infinity;
      return s1 - s2;
    });
  }, [schemaData?.buttons, props.customButtons]);

  const formSchema = useMemo(() => {
    if (!schemaData?.schema) {
      return undefined;
    }
    const transformed = props.formSchemaTransform
      ? props.formSchemaTransform(schemaData.schema, editingRecord)
      : schemaData.schema;
    return sortSchemaProperties(transformed);
  }, [schemaData?.schema, props.formSchemaTransform, editingRecord]);

  return {
    t,
    status: {
      bootLoading,
      tableLoading,
      submitLoading,
      submitError,
      drawerSchemaLoading,
      hasSchemaError,
      hasPageError,
      showPageWarning,
      pageErrorMessage: resolveSimpleTableLoadError(pageError, t('table.pageLoadFail', '列表数据加载失败，请稍后重试。')),
    },
    schema: {
      data: schemaData,
      formSchema,
      error: schemaError,
      dictionariesError,
      retryDictionaries,
      retry: refetchSchema,
    },
    table: {
      data: pageData,
      filters,
      sorter: sort,
      onQueryChange: (nextFilters, nextSort) => {setFilters(nextFilters); setSort(nextSort); setPage(1);},
      buttons: mergedButtons,
      onChange: handleTableChange,
      onFilterChange: handleFilterChange,
      refresh: refreshPage,
      refreshDisabled: submitLoading,
    },
    drawer: {
      open: drawerOpen,
      editingRecord,
      setOpen: setDrawerOpen,
      close: () => setDrawerOpen(false),
    },
    actions: {
      defaultEvents: {
        add: handleAdd,
        edit: handleEdit,
        delete: handleDelete,
        del: handleDelete,
      },
      handleFormSubmit,
      retryPage: refetchPage,
    },
  };
}
