export type CatalogSyncNotice = {
  key: string;
  fallback: string;
  tone: 'warning' | 'error';
};

export type CatalogSyncLabel = {
  key: string;
  fallback: string;
};

export type CatalogStatusLabel = CatalogSyncLabel & {
  tone: 'success' | 'error' | 'warning' | 'default';
};

export const catalogSyncNotices: Record<string, CatalogSyncNotice> = {
  OFFICIAL_MCP_SYNC_LOCK_BUSY: {
    key: 'ai.catalog.warning.syncLockBusy',
    fallback: '另一个官方 Registry 同步任务正在运行，请稍后重试',
    tone: 'warning',
  },
  OFFICIAL_MCP_SYNC_PAGE_BUDGET_REACHED: {
    key: 'ai.catalog.warning.syncPageBudget',
    fallback: '本轮同步已达到页数上限，将从保存的游标继续',
    tone: 'warning',
  },
  OFFICIAL_MCP_DESCRIPTOR_SERIALIZATION_FAILED: {
    key: 'ai.catalog.error.syncDescriptor',
    fallback: 'Registry 描述信息处理失败',
    tone: 'error',
  },
  OFFICIAL_MCP_SYNC_FAILED: {
    key: 'ai.catalog.error.syncUnknown',
    fallback: '官方 MCP Registry 同步失败',
    tone: 'error',
  },
};

const unknownCatalogSyncNotice: CatalogSyncNotice = {
  key: 'ai.catalog.error.syncUnknown',
  fallback: '官方 MCP Registry 同步失败',
  tone: 'error',
};

export const resolveCatalogSyncNotice = (
  errorCode?: string,
): CatalogSyncNotice | undefined => {
  if (!errorCode) return undefined;
  return catalogSyncNotices[errorCode] ?? unknownCatalogSyncNotice;
};

export const catalogSyncStatusLabels: Record<string, CatalogSyncLabel> = {
  NEVER: {
    key: 'ai.catalog.status.never',
    fallback: '未同步',
  },
  RUNNING: {
    key: 'ai.catalog.status.running',
    fallback: '同步中',
  },
  SUCCEEDED: {
    key: 'ai.catalog.status.succeeded',
    fallback: '已同步',
  },
  PARTIAL: {
    key: 'ai.catalog.status.partial',
    fallback: '部分完成',
  },
  FAILED: {
    key: 'ai.catalog.status.failed',
    fallback: '同步失败',
  },
};

const unknownCatalogSyncStatus: CatalogSyncLabel = {
  key: 'ai.catalog.status.unknown',
  fallback: '状态未知',
};

export const resolveCatalogSyncStatus = (
  status?: string,
): CatalogSyncLabel => {
  if (!status) return catalogSyncStatusLabels.NEVER;
  return catalogSyncStatusLabels[status] ?? unknownCatalogSyncStatus;
};

export const catalogSyncModeLabels: Record<string, CatalogSyncLabel> = {
  FULL: {
    key: 'ai.catalog.mode.full',
    fallback: '全量',
  },
  INCREMENTAL: {
    key: 'ai.catalog.mode.incremental',
    fallback: '增量',
  },
};

const unknownCatalogSyncMode: CatalogSyncLabel = {
  key: 'ai.catalog.mode.unknown',
  fallback: '模式未知',
};

export const resolveCatalogSyncMode = (
  mode?: string,
): CatalogSyncLabel => {
  if (!mode) return catalogSyncModeLabels.FULL;
  return catalogSyncModeLabels[mode] ?? unknownCatalogSyncMode;
};

export const catalogItemStatusLabels: Record<string, CatalogStatusLabel> = {
  ACTIVE: {
    key: 'ai.catalog.itemStatus.active',
    fallback: '可用',
    tone: 'success',
  },
  READY: {
    key: 'ai.catalog.itemStatus.ready',
    fallback: '就绪',
    tone: 'success',
  },
  DRAFT: {
    key: 'ai.catalog.itemStatus.draft',
    fallback: '草稿',
    tone: 'default',
  },
  ERROR: {
    key: 'ai.catalog.itemStatus.error',
    fallback: '异常',
    tone: 'error',
  },
  DISABLED: {
    key: 'ai.catalog.itemStatus.disabled',
    fallback: '已停用',
    tone: 'warning',
  },
  DELETED: {
    key: 'ai.catalog.itemStatus.deleted',
    fallback: '已下架',
    tone: 'default',
  },
};

const unknownCatalogItemStatus: CatalogStatusLabel = {
  key: 'ai.catalog.itemStatus.unknown',
  fallback: '状态未知',
  tone: 'default',
};

export const resolveCatalogItemStatus = (
  status?: string,
): CatalogStatusLabel => catalogItemStatusLabels[status ?? '']
  ?? unknownCatalogItemStatus;

export const catalogOperationErrors: Record<string, CatalogSyncLabel> = {
  AI_CATALOG_REQUEST_INVALID: {
    key: 'ai.catalog.error.requestInvalid',
    fallback: '请求内容无效，请检查后重试',
  },
  AI_CATALOG_OPERATION_UNAVAILABLE: {
    key: 'ai.catalog.error.operationUnavailable',
    fallback: '当前操作暂不可用，请刷新数据后重试',
  },
  AI_CATALOG_OPERATION_FAILED: {
    key: 'ai.catalog.error.operationFailed',
    fallback: '扩展市场操作失败，请稍后重试',
  },
};

const errorCodeOf = (error: unknown): string | undefined => {
  if (!error || typeof error !== 'object') return undefined;
  const candidate = error as {
    code?: unknown;
    data?: {errorCode?: unknown};
  };
  if (typeof candidate.code === 'string') return candidate.code;
  return typeof candidate.data?.errorCode === 'string'
    ? candidate.data.errorCode
    : undefined;
};

export const resolveCatalogOperationError = (
  error: unknown,
  fallback: CatalogSyncLabel,
): CatalogSyncLabel => {
  const errorCode = errorCodeOf(error);
  return (errorCode && catalogOperationErrors[errorCode]) || fallback;
};
