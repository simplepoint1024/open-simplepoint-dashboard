import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
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
import {usePageActive, usePageRetention} from '@simplepoint/shared/page/hooks';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {hasRecordIdentity} from './recordIdentity';
import {defaultCrudData} from './defaultCrudData';
import {transportFilters} from '../Table/query';
import {resolveSimpleTableLoadError} from './errorMessage';
import {reachableServerPage} from './pagination';
import {prepareFormSubmission, claimFormSubmission, startFormWrite, cancelFormPreparation, cancelFormValidation, finishFormSubmission, type FormSubmission} from './formSubmission';

const nsLoadedCache = new Set<string>();

export type SimpleTableController<T> = {
  t: ReturnType<typeof useI18n>['t'];
  status: {
    bootLoading: boolean;
    tableLoading: boolean;
    submitLoading: boolean;
    submitError?: string;
    isSubmitting: () => boolean;
    deleteOpen: boolean;
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
    selectionResetKey: number;
  };
  drawer: {
    open: boolean;
    editingRecord: any | null;
    setOpen: (open: boolean) => void;
    close: () => void;
    canClose: () => boolean;
  };
  deletion: {open: boolean; count: number; error?: string; confirm: () => Promise<void>; cancel: () => void};
  actions: {
    defaultEvents: Record<string, (selectedRowKeys: React.Key[], selectedRows: T[], props?: TableButtonProps) => void>;
    handleFormSubmit: (event: IChangeEvent) => Promise<void>;
    beginFormSubmit: () => boolean;
    handleFormError: () => void;
    retryPage: () => Promise<unknown>;
  };
};

export function useSimpleTableController<T = any>(props: SimpleTableProps<T>): SimpleTableController<T> {
  const scope = useQueryScope();
  const resourceKey = JSON.stringify([props.baseUrl, props.name, props.queryContext ?? '']);
  const session = JSON.stringify([scope, resourceKey]);
  const { t, ensure, locale, ready } = useI18n();
  const {message} = App.useApp();
  const operation = useOperationScope(resourceKey);
  const active = usePageActive(), activity = useRef(active); activity.current = active;
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
  const [selectionResetKey, setSelectionResetKey] = useState(0);
  useEffect(() => {setSubmitError(undefined);}, [props.drawerOpen]);
  const [deletion, setDeletion] = useState<{keys: React.Key[]; scope: string; error?: string}>();
  const confirmingDelete = !!deletion && deletion.scope === session;
  const submissionLock = useRef(false);
  const formSubmission = useRef<FormSubmission<{action: SimpleTableSubmitAction; editingRecord: any | null}> | undefined>(undefined);
  useLayoutEffect(() => {
    if (active && operation.selected) return;
    const pending = cancelFormPreparation(formSubmission, session); if (!pending) return;
    operation.finish(pending.request);
    if (operation.current()) {submissionLock.current = false; setSubmitLoading(false);}
  }, [active, operation.selected, session, operation.finish, operation.current]);
  const previousScope = useRef(session);
  useLayoutEffect(() => {
    const next = session; if (previousScope.current === next) return;
    previousScope.current = next; submissionLock.current = false; formSubmission.current = undefined;
    setDeletion(undefined); setSubmitLoading(false); setSubmitError(undefined);
    setSelectionResetKey(previous => previous + 1);
    setInnerDrawerOpen(false); setInnerEditing(null); setPage(1); setFilters(props.initialFilters ?? {}); setSort(undefined);
    props.onDrawerOpenChange?.(false); props.onEditingRecordChange?.(null);
  }, [session]);

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

  const { data: rawSchemaData, isLoading: schemaLoading, error: schemaError, refetch: refetchSchema,
    dictionariesLoading, dictionariesError, retryDictionaries } = useSchema(props.baseUrl, {staleTime: props.schemaStaleTime});
  const schemaData = useMemo(() => rawSchemaData && props.schemaTransform
    ? {...rawSchemaData, schema: props.schemaTransform(rawSchemaData.schema)} : rawSchemaData,
  [rawSchemaData, props.schemaTransform]);

  const drawerOpen = props.drawerOpen ?? innerDrawerOpen;
  const interactionLocked = drawerOpen || submitLoading || confirmingDelete;
  useLayoutEffect(() => {props.onInteractionLockChange?.(interactionLocked);}, [props.onInteractionLockChange, interactionLocked]);
  // Custom routes remain protected unless their owner explicitly opts in.
  usePageRetention(props.allowTabEviction === true && !interactionLocked, interactionLocked);
  const updateDrawerOpen = (open: boolean) => {
    props.onDrawerOpenChange?.(open);
    if (props.drawerOpen === undefined) setInnerDrawerOpen(open);
  };
  const canCloseDrawer = () => operation.current() && operation.isSelected() && !operation.isBusy();
  const setDrawerOpen = (open: boolean) => {if (canCloseDrawer()) updateDrawerOpen(open);};

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
  useEffect(() => {
    if (pageFetching || pageError) return;
    const reachable = reachableServerPage(page, pageData?.page);
    if (reachable !== page) setPage(reachable);
  }, [page, pageData, pageFetching, pageError]);
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
    const button = schemaData?.buttons?.find((item: TableButtonProps) => item.key === 'add');
    if (!button || schemaError || !operation.current() || !operation.isSelected() || operation.isBusy() || drawerOpen || confirmingDelete || props.isButtonDisabled?.(button, [], [])) return;
    props.onInteractionLockChange?.(true);
    setEditingRecord(null);
    setDrawerOpen(true);
  };

  const handleEdit = (_keys: React.Key[], rows: any[]) => {
    const button = schemaData?.buttons?.find((item: TableButtonProps) => item.key === 'edit');
    if (!button || schemaError || !operation.current() || !operation.isSelected() || operation.isBusy() || drawerOpen || confirmingDelete
      || rows?.length !== 1 || !hasRecordIdentity(rows[0]) || props.isButtonDisabled?.(button, _keys, rows)) return;
    props.onInteractionLockChange?.(true);
    setEditingRecord(rows?.[0] ?? null);
    setDrawerOpen(true);
  };

  const handleDelete = (keys: React.Key[], rows: any[]) => {
    const button = schemaData?.buttons?.find((item: TableButtonProps) => item.key === 'delete' || item.key === 'del');
    if (!button || schemaError || !operation.current() || !operation.isSelected() || operation.isBusy() || drawerOpen || confirmingDelete
      || !keys.length || rows?.length !== keys.length || rows.some(row => !hasRecordIdentity(row))
      || props.isButtonDisabled?.(button, keys, rows)) return;
    props.onInteractionLockChange?.(true);
    setDeletion({keys: [...keys], scope: session});
  };

  const confirmDelete = async () => {
    if (!deletion || !confirmingDelete || submissionLock.current || schemaError
      || !schemaData?.buttons?.some((button: TableButtonProps) => button.key === 'delete' || button.key === 'del')) return;
    const request = operation.begin(); if (!request) return;
    submissionLock.current = true; setSubmitLoading(true); setDeletion({...deletion, error: undefined});
    try {
      await del(props.baseUrl, deletion.keys as any, {signal: request.signal, notifyOnError: false});
      if (!operation.current(request.signal)) return;
      setDeletion(undefined);
      setSelectionResetKey(previous => previous + 1);
      if (operation.isSelected()) message.success(t('table.deleteSuccess', '删除成功'));
      await refreshTargets(deleteRefreshTargets);
    } catch (error) {
      if (!operation.current(request.signal)) return;
      const detail = resolveSimpleTableOperationError(error, 'delete', props.errorMessageResolver, () => resolveApiErrorMessage(error, ''));
      setDeletion({...deletion, error: t('table.deleteFail', '删除失败: {msg}', {msg: detail})});
    } finally {
      if (operation.current()) submissionLock.current = false;
      operation.finish(request);
      if (operation.current()) setSubmitLoading(false);
    }
  };

  const beginFormSubmit = () => {
    if (!drawerOpen || !activity.current || !operation.current() || !operation.isSelected() || submissionLock.current || operation.isBusy()) return false;
    const action: SimpleTableSubmitAction = editingRecord ? 'edit' : 'add';
    if (schemaError || confirmingDelete || !schemaData?.buttons?.some((button: TableButtonProps) => button.key === action)) {
      setSubmitError(t('table.actionUnavailable', '此操作暂不可用，请刷新页面后重试')); return false;
    }
    const request = operation.begin(); if (!request) return false;
    if (!prepareFormSubmission(formSubmission, session, request, {action, editingRecord: editingRecord ? {...editingRecord} : null})) {
      operation.finish(request); return false;
    }
    submissionLock.current = true;
    setSubmitLoading(true); setSubmitError(undefined); return true;
  };
  const handleFormError = () => {
    const pending = cancelFormValidation(formSubmission, session); if (!pending) return;
    operation.finish(pending.request);
    if (operation.current()) {submissionLock.current = false; setSubmitLoading(false);}
  };
  const handleFormSubmit = async ({ formData }: IChangeEvent) => {
    // Only the callback belonging to an accepted pre-validation intent can write.
    const submission = claimFormSubmission(formSubmission, session); if (!submission) return;
    const {request, target: {action, editingRecord: capturedEditing}} = submission;
    try {
      if (!operation.current(request.signal)) return;
      const beforeSubmitContext: SimpleTableBeforeSubmitContext = {
        action,
        formData,
        currentEditing: capturedEditing,
        baseUrl: props.baseUrl,
        signal: request.signal,
      };
      const normalizedFormData = props.beforeSubmit
        ? await props.beforeSubmit(beforeSubmitContext)
        : formData;
      let submittedData = normalizedFormData ?? formData;
      if (!operation.current(request.signal) || !operation.isSelected() || !activity.current || !startFormWrite(formSubmission, submission)) return;

      let result: unknown;
      if (props.onSubmit) {
        result = await props.onSubmit(action, submittedData, capturedEditing);
      } else if (action === 'edit') {
        submittedData = defaultCrudData(schemaData?.schema, { ...capturedEditing, ...submittedData }, capturedEditing);
        if (props.defaultSubmitDataTransform) submittedData = props.defaultSubmitDataTransform(submittedData, beforeSubmitContext);
        if (!operation.current(request.signal) || !operation.isSelected() || !activity.current) return;
        result = await put(props.baseUrl, submittedData, {signal: request.signal, notifyOnError: false});
        if (operation.current(request.signal) && operation.isSelected()) message.success(t('table.editSuccess', '修改成功'));
      } else {
        submittedData = defaultCrudData(schemaData?.schema, submittedData);
        if (props.defaultSubmitDataTransform) submittedData = props.defaultSubmitDataTransform(submittedData, beforeSubmitContext);
        if (!operation.current(request.signal) || !operation.isSelected() || !activity.current) return;
        result = await post(props.baseUrl, submittedData, {signal: request.signal, notifyOnError: false});
        if (operation.current(request.signal) && operation.isSelected()) message.success(t('table.addSuccess', '新增成功'));
      }
      if (!operation.current(request.signal)) return;

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

      if (!operation.current(request.signal)) return;

      updateDrawerOpen(false);
      setEditingRecord(null);
      await refreshTargets(submitRefreshTargets);
    } catch (e: any) {
      if (!operation.current(request.signal)) return;
      if (isHttpError(e) && e.status === 401) {
        return;
      }
      const detail = resolveSimpleTableOperationError(
        e,
        action,
        props.errorMessageResolver,
        () => resolveApiErrorMessage(e, ''),
      );
      setSubmitError(detail);
      if (operation.isSelected()) message.error(t('table.actionFail', '操作失败: {msg}', {msg: detail}));
    } finally {
      const released = finishFormSubmission(formSubmission, submission);
      if (released && operation.current()) submissionLock.current = false;
      operation.finish(request);
      if (released && operation.current()) setSubmitLoading(false);
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
      isSubmitting: operation.isBusy,
      deleteOpen: confirmingDelete,
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
      selectionResetKey,
    },
    drawer: {
      open: drawerOpen,
      editingRecord,
      setOpen: setDrawerOpen,
      close: () => setDrawerOpen(false),
      canClose: canCloseDrawer,
    },
    deletion: {open: confirmingDelete, count: deletion?.keys.length ?? 0, error: deletion?.error, confirm: confirmDelete,
      cancel: () => {if (!operation.isBusy()) setDeletion(undefined);}},
    actions: {
      defaultEvents: {
        add: handleAdd,
        edit: handleEdit,
        delete: handleDelete,
        del: handleDelete,
      },
      handleFormSubmit,
      beginFormSubmit,
      handleFormError,
      retryPage: refetchPage,
    },
  };
}
