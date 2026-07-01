import { useEffect, useMemo, useState } from 'react';
import type { IChangeEvent } from '@rjsf/core';
import { Modal, message } from 'antd';
import { useSchema } from '@simplepoint/shared/hooks/useSchema';
import { HttpError } from '@simplepoint/shared/api/client';
import { del, get, post, put, usePage } from '@simplepoint/shared/api/methods';
import { useI18n } from '@simplepoint/shared/hooks/useI18n';
import { getStoredContextId, getStoredTenantId } from '@simplepoint/shared/api/contextId';
import type { TableButtonProps } from '../Table';
import type { SimpleTableAfterSubmitContext, SimpleTableBeforeSubmitContext, SimpleTableProps, SimpleTableRefreshTargets, SimpleTableSubmitAction } from './types';

const nsLoadedCache = new Set<string>();

export type SimpleTableController<T> = {
  t: ReturnType<typeof useI18n>['t'];
  status: {
    bootLoading: boolean;
    tableLoading: boolean;
    submitLoading: boolean;
    drawerSchemaLoading: boolean;
    hasSchemaError: boolean;
    hasPageError: boolean;
    showPageWarning: boolean;
  };
  schema: {
    data: any;
    formSchema: any;
    error: unknown;
    retry: () => Promise<unknown>;
  };
  table: {
    data: any;
    filters: Record<string, string>;
    sorter: string | undefined;
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
  const { t, ensure, locale, ready } = useI18n();
  const [i18nReady, setI18nReady] = useState(false);
  const [page, setPage] = useState<number>(1);
  const [size, setSize] = useState<number>(20);
  const [filters, setFilters] = useState<Record<string, string>>(props.initialFilters ?? {});
  const [sort, setSort] = useState<string | undefined>(undefined);
  const [tenantId, setTenantId] = useState<string>(() => getStoredTenantId() ?? '');
  const [contextId, setContextId] = useState<string>(() => getStoredContextId(getStoredTenantId()) ?? '');
  const [innerDrawerOpen, setInnerDrawerOpen] = useState(false);
  const [innerEditing, setInnerEditing] = useState<any | null>(null);
  const [submitLoading, setSubmitLoading] = useState(false);

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

  const { data: schemaData, isLoading: schemaLoading, error: schemaError, refetch: refetchSchema } = useSchema(
    props.baseUrl,
    { enabled: i18nReady }
  );

  const drawerOpen = props.drawerOpen ?? innerDrawerOpen;
  const setDrawerOpen = (open: boolean) => {
    props.onDrawerOpenChange?.(open);
    if (props.drawerOpen === undefined) setInnerDrawerOpen(open);
  };

  const editingRecord = props.editingRecord ?? innerEditing;
  const setEditingRecord = (rec: any | null) => {
    props.onEditingRecordChange?.(rec);
    if (props.editingRecord === undefined) setInnerEditing(rec);
  };

  const fetchPage = () =>
    get<import('@simplepoint/shared/types/request').Page<any>>(props.baseUrl, {
      page: page - 1,
      size,
      ...filters,
      ...(sort ? {sort} : {}),
    });

  const { data: pageData, isLoading: pageLoading, error: pageError, refetch: refetchPage } = usePage(
    [props.name, tenantId, contextId, page, size, filters, sort],
    fetchPage
  );

  useEffect(() => {
    const handleTenantChange = (event: Event) => {
      const nextTenantId = (event as CustomEvent<string | undefined>).detail ?? getStoredTenantId() ?? '';
      setTenantId(nextTenantId);
      setContextId(getStoredContextId(nextTenantId) ?? '');
    };

    const handleContextChange = (event: Event) => {
      const detail = (event as CustomEvent<{ tenantId?: string; contextId?: string }>).detail;
      if (detail && typeof detail === 'object') {
        if ((detail.tenantId ?? '') !== tenantId) {
          return;
        }
        setContextId(detail.contextId ?? '');
        return;
      }
      setContextId(getStoredContextId(tenantId) ?? '');
    };

    window.addEventListener('sp-set-tenant', handleTenantChange as EventListener);
    window.addEventListener('sp-set-context-id', handleContextChange as EventListener);

    return () => {
      window.removeEventListener('sp-set-tenant', handleTenantChange as EventListener);
      window.removeEventListener('sp-set-context-id', handleContextChange as EventListener);
    };
  }, [tenantId]);

  useEffect(() => {
    setPage(1);
  }, [tenantId, contextId]);

  const bootLoading = !ready || !i18nReady || (schemaLoading && !schemaData) || (!pageData && pageLoading && !pageError);
  const tableLoading = !bootLoading && pageLoading;
  const drawerSchemaLoading = drawerOpen && schemaLoading && !schemaData;
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

  const handleTableChange = (pagination: any, _tableFilters: any, sorter: any) => {
    setPage(pagination?.current ?? 1);
    setSize(pagination?.pageSize ?? size);
    // AntD sorter: { field: string | string[], order: 'ascend'|'descend'|undefined }
    const field = Array.isArray(sorter?.field)
      ? (sorter.field as string[]).join('.')
      : (sorter?.field as string | undefined);
    const dir = sorter?.order === 'ascend' ? 'asc' : sorter?.order === 'descend' ? 'desc' : undefined;
    setSort(field && dir ? `${field},${dir}` : undefined);
    void refetchPage();
  };

  const handleFilterChange = (nextFilters: Record<string, string>) => {
    setFilters(nextFilters);
    setPage(1);
    void refetchPage();
  };

  const handleAdd = () => {
    setEditingRecord(null);
    setDrawerOpen(true);
  };

  const handleEdit = (_keys: React.Key[], rows: any[]) => {
    setEditingRecord(rows?.[0] ?? null);
    setDrawerOpen(true);
  };

  const handleDelete = (keys: React.Key[]) => {
    Modal.confirm({
      title: t('table.confirmDeleteTitle', '确认删除'),
      content: t('table.confirmDeleteContent', '确定要删除选中的 {count} 条数据吗？', { count: keys.length }),
      onOk: async () => {
        setSubmitLoading(true);
        try {
          await del(props.baseUrl, keys as any);
          message.success(t('table.deleteSuccess', '删除成功'));
          await refreshTargets(deleteRefreshTargets);
        } catch (e: any) {
          if (e instanceof HttpError && e.status === 401) {
            return;
          }
          message.error(t('table.deleteFail', '删除失败: {msg}', { msg: e?.userMessage || e?.message || '' }));
        } finally {
          setSubmitLoading(false);
        }
      },
    });
  };

  const handleFormSubmit = async ({ formData }: IChangeEvent) => {
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
      if (e instanceof HttpError && e.status === 401) {
        return;
      }
      message.error(t('table.actionFail', '操作失败: {msg}', { msg: e?.userMessage || e?.message || '' }));
    } finally {
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
    if (!props.formSchemaTransform) {
      return schemaData.schema;
    }
    return props.formSchemaTransform(schemaData.schema, editingRecord);
  }, [schemaData?.schema, props.formSchemaTransform, editingRecord]);

  return {
    t,
    status: {
      bootLoading,
      tableLoading,
      submitLoading,
      drawerSchemaLoading,
      hasSchemaError,
      hasPageError,
      showPageWarning,
    },
    schema: {
      data: schemaData,
      formSchema,
      error: schemaError,
      retry: refetchSchema,
    },
    table: {
      data: pageData,
      filters,
      sorter: sort,
      buttons: mergedButtons,
      onChange: handleTableChange,
      onFilterChange: handleFilterChange,
      refresh: () => void refetchPage(),
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
