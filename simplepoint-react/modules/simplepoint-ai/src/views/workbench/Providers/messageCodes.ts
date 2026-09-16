export type ProviderMessageDescriptor = {
  key: string;
  fallback: string;
};

export type ProviderErrorDescriptor = ProviderMessageDescriptor;

export type ProviderStatusDescriptor = ProviderMessageDescriptor & {
  tone: 'success' | 'error' | 'default';
};

export const providerMessageDescriptors: Record<
  string,
  ProviderMessageDescriptor
> = {
  CONNECTION_TEST_SUCCEEDED: {
    key: 'ai.providers.message.connectionTestSucceeded',
    fallback: '连接测试成功',
  },
  CONNECTION_TEST_FAILED: {
    key: 'ai.providers.message.connectionTestFailed',
    fallback: '连接测试失败',
  },
  MODEL_SYNC_SUCCEEDED: {
    key: 'ai.providers.message.modelSyncSucceeded',
    fallback: '模型同步成功',
  },
  MODEL_SYNC_FAILED: {
    key: 'ai.providers.message.modelSyncFailed',
    fallback: '模型同步失败',
  },
};

const unknownProviderMessage: ProviderMessageDescriptor = {
  key: 'ai.providers.message.unknown',
  fallback: '最近操作已完成，请查看当前状态',
};

export const resolveProviderMessage = (
  messageCode?: string,
): ProviderMessageDescriptor | undefined => {
  if (!messageCode) return undefined;
  return providerMessageDescriptors[messageCode] ?? unknownProviderMessage;
};

export const providerStatusDescriptors: Record<
  string,
  ProviderStatusDescriptor
> = {
  SUCCESS: {
    key: 'ai.providers.status.connectionSucceeded',
    fallback: '连接成功',
    tone: 'success',
  },
  SYNCED: {
    key: 'ai.providers.status.synchronized',
    fallback: '已同步',
    tone: 'success',
  },
  FAILED: {
    key: 'ai.providers.status.failed',
    fallback: '操作失败',
    tone: 'error',
  },
};

const untestedProviderStatus: ProviderStatusDescriptor = {
  key: 'ai.common.notTested',
  fallback: '未测试',
  tone: 'default',
};

const unknownProviderStatus: ProviderStatusDescriptor = {
  key: 'ai.providers.status.unknown',
  fallback: '状态未知',
  tone: 'default',
};

export const resolveProviderStatus = (
  status?: string,
): ProviderStatusDescriptor => {
  if (!status) return untestedProviderStatus;
  return providerStatusDescriptors[status] ?? unknownProviderStatus;
};

export const providerErrorDescriptors: Record<
  string,
  ProviderErrorDescriptor
> = {
  AI_PROVIDER_ID_REQUIRED: {
    key: 'ai.providers.error.idRequired',
    fallback: '请选择一个模型接入',
  },
  AI_PROVIDER_NOT_FOUND: {
    key: 'ai.providers.error.notFound',
    fallback: '模型接入不存在或已被删除',
  },
  AI_PROVIDER_DISABLED: {
    key: 'ai.providers.error.disabled',
    fallback: '模型接入已禁用，请先启用后再操作',
  },
  AI_PROVIDER_REQUEST_INVALID: {
    key: 'ai.providers.error.requestInvalid',
    fallback: '模型接入配置无效，请检查后重试',
  },
  AI_PROVIDER_OPERATION_UNAVAILABLE: {
    key: 'ai.providers.error.operationUnavailable',
    fallback: '当前模型接入操作暂不可用，请刷新后重试',
  },
  AI_PROVIDER_RATE_LIMITED: {
    key: 'ai.providers.error.rateLimited',
    fallback: '模型接入请求过于频繁，请稍后再试',
  },
  AI_PROVIDER_CONNECTION_TEST_FAILED: {
    key: 'ai.providers.page.error.test',
    fallback: '连接测试失败',
  },
  AI_PROVIDER_MODEL_DISCOVERY_FAILED: {
    key: 'ai.providers.page.error.discover',
    fallback: '获取模型列表失败',
  },
  AI_PROVIDER_MODEL_SYNC_FAILED: {
    key: 'ai.providers.page.error.sync',
    fallback: '同步模型列表失败',
  },
  AI_PROVIDER_OPERATION_FAILED: {
    key: 'ai.providers.page.error.operation',
    fallback: '模型接入操作失败，请稍后重试',
  },
};

const unknownProviderError: ProviderErrorDescriptor = {
  key: 'ai.providers.page.error.operation',
  fallback: '模型接入操作失败，请稍后重试',
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

export const resolveProviderOperationError = (
  error: unknown,
  fallback: ProviderErrorDescriptor = unknownProviderError,
): ProviderErrorDescriptor => {
  const errorCode = errorCodeOf(error);
  return (errorCode && providerErrorDescriptors[errorCode]) || fallback;
};
