export type WorkbenchErrorDescriptor = {
  key: string;
  fallback: string;
};

type Translate = (key: string, fallback?: string) => string;

const requestInvalidDescriptor: WorkbenchErrorDescriptor = {
  key: 'ai.error.requestInvalid',
  fallback: '提交内容无效，请检查填写内容后重试',
};

const operationConflictDescriptor: WorkbenchErrorDescriptor = {
  key: 'ai.error.operationConflict',
  fallback: '当前状态不允许该操作，请刷新后重试',
};

const operationRejectedDescriptor: WorkbenchErrorDescriptor = {
  key: 'ai.error.operationRejected',
  fallback: '当前请求无法执行，请检查配置后重试',
};

export const workbenchErrorDescriptors: Record<
  string,
  WorkbenchErrorDescriptor
> = {
  AI_MODEL_REQUEST_INVALID: {
    key: 'ai.models.error.requestInvalid',
    fallback: '模型配置无效，请检查后重试',
  },
  AI_MODEL_OPERATION_UNAVAILABLE: {
    key: 'ai.models.error.operationUnavailable',
    fallback: '当前模型操作暂不可用，请刷新后重试',
  },
  AI_MODEL_OPERATION_FAILED: {
    key: 'ai.models.error.operationFailed',
    fallback: '模型操作失败，请稍后重试',
  },
  AI_API_KEY_REQUEST_INVALID: {
    key: 'ai.api-keys.error.requestInvalid',
    fallback: 'API Key 配置无效，请检查后重试',
  },
  AI_API_KEY_OPERATION_UNAVAILABLE: {
    key: 'ai.api-keys.error.operationUnavailable',
    fallback: '当前 API Key 操作暂不可用，请刷新后重试',
  },
  AI_API_KEY_OPERATION_FAILED: {
    key: 'ai.api-keys.error.operationFailed',
    fallback: 'API Key 操作失败，请稍后重试',
  },
  AI_BILLING_RANGE_INVALID: {
    key: 'ai.billing.error.rangeInvalid',
    fallback: '计费时间范围无效，请重新选择',
  },
  AI_BILLING_QUERY_FAILED: {
    key: 'ai.billing.error.queryFailed',
    fallback: '计费数据查询失败，请稍后重试',
  },
  AI_MODEL_DEBUG_REQUEST_INVALID: {
    key: 'ai.model-debug.error.requestInvalid',
    fallback: '调试请求无效，请检查对话内容后重试',
  },
  AI_MODEL_DEBUG_OPERATION_UNAVAILABLE: {
    key: 'ai.model-debug.error.operationUnavailable',
    fallback: '当前模型暂不可调试，请刷新后重试',
  },
  AI_MODEL_DEBUG_PROVIDER_FAILED: {
    key: 'ai.model-debug.error.providerFailed',
    fallback: '模型接入调用失败，请稍后重试',
  },
  AI_MODEL_DEBUG_BUSY: {
    key: 'ai.model-debug.error.busy',
    fallback: '当前调试请求较多，请稍后重试',
  },
  AI_MODEL_DEBUG_GENERATION_FAILED: {
    key: 'ai.model-debug.error.generationFailed',
    fallback: '模型生成失败，请稍后重试',
  },
  AI_MODEL_DEBUG_FAILED: {
    key: 'ai.model-debug.error.failed',
    fallback: '模型调试失败，请稍后重试',
  },
  AI_MODEL_DEBUG_STREAM_UNAVAILABLE: {
    key: 'ai.model-debug.error.streamUnavailable',
    fallback: '流式响应正文不可用',
  },
  AI_MODEL_DEBUG_INCOMPLETE: {
    key: 'ai.model-debug.error.incomplete',
    fallback: '模型响应未正常完成',
  },
  AI_AGENT_REQUEST_INVALID: requestInvalidDescriptor,
  AI_AGENT_OPERATION_CONFLICT: operationConflictDescriptor,
  AI_WORKFLOW_REQUEST_INVALID: requestInvalidDescriptor,
  AI_WORKFLOW_OPERATION_CONFLICT: operationConflictDescriptor,
  AI_SKILL_REQUEST_INVALID: requestInvalidDescriptor,
  AI_SKILL_OPERATION_CONFLICT: operationConflictDescriptor,
  AI_KNOWLEDGE_REQUEST_INVALID: requestInvalidDescriptor,
  AI_KNOWLEDGE_OPERATION_CONFLICT: operationConflictDescriptor,
  AI_MCP_SERVER_REQUEST_INVALID: requestInvalidDescriptor,
  AI_MCP_SERVER_OPERATION_CONFLICT: operationConflictDescriptor,
  AI_MCP_PUBLICATION_REQUEST_INVALID: requestInvalidDescriptor,
  AI_MCP_PUBLICATION_OPERATION_CONFLICT: operationConflictDescriptor,
  AI_MCP_INVOCATION_REQUEST_INVALID: requestInvalidDescriptor,
  AI_MCP_TASK_REQUEST_INVALID: requestInvalidDescriptor,
  AI_RUNTIME_MCP_PROFILE_REQUEST_INVALID: requestInvalidDescriptor,
  AI_RUNTIME_NODE_REQUEST_INVALID: requestInvalidDescriptor,
  AI_RUNTIME_POOL_REQUEST_INVALID: requestInvalidDescriptor,
  AI_RUNTIME_SECRET_REQUEST_INVALID: requestInvalidDescriptor,
  AI_RUNTIME_WORKLOAD_REQUEST_INVALID: requestInvalidDescriptor,
  AI_RUNTIME_REQUEST_REJECTED: operationRejectedDescriptor,
  AI_RUNTIME_NODE_FENCED: {
    key: 'ai.error.runtimeNodeFenced',
    fallback: 'Runtime Node 已被隔离，请重新注册或选择其他节点',
  },
  AI_RUNTIME_NODE_NOT_REGISTERED: {
    key: 'ai.error.runtimeNodeNotRegistered',
    fallback: 'Runtime Node 尚未注册，请先完成节点注册',
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

export const resolveWorkbenchErrorCode = (
  errorCode: string | undefined,
  fallback: WorkbenchErrorDescriptor,
): WorkbenchErrorDescriptor => (
  (errorCode && workbenchErrorDescriptors[errorCode]) || fallback
);

export const resolveWorkbenchOperationError = (
  error: unknown,
  fallback: WorkbenchErrorDescriptor,
): WorkbenchErrorDescriptor => resolveWorkbenchErrorCode(
  errorCodeOf(error),
  fallback,
);

export const localizeWorkbenchError = (
  t: Translate,
  descriptor: WorkbenchErrorDescriptor,
) => t(descriptor.key, descriptor.fallback);

export const localizeWorkbenchOperationError = (
  t: Translate,
  error: unknown,
  fallback: WorkbenchErrorDescriptor,
) => localizeWorkbenchError(t, resolveWorkbenchOperationError(error, fallback));
