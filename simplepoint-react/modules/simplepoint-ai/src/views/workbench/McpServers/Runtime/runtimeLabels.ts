type Translate = (key: string, fallback?: string) => string;

export const runtimeStatusLabel = (
  t: Translate,
  status?: string,
) => {
  switch (status) {
    case 'REGISTERING':
      return t('ai.runtime.status.registering', '注册中');
    case 'READY':
      return t('ai.runtime.status.ready', '就绪');
    case 'DRAINING':
      return t('ai.runtime.status.draining', '排空中');
    case 'ERROR':
      return t('ai.runtime.status.error', '异常');
    case 'OFFLINE':
      return t('ai.runtime.status.offline', '离线');
    case 'SCALING':
      return t('ai.runtime.status.scaling', '伸缩中');
    case 'IDLE':
      return t('ai.runtime.status.idle', '空闲');
    case 'DISABLED':
      return t('ai.runtime.status.disabled', '已停用');
    case 'PENDING':
      return t('ai.runtime.status.pending', '等待调度');
    case 'ASSIGNED':
      return t('ai.runtime.status.assigned', '已分配');
    case 'STARTING':
      return t('ai.runtime.status.starting', '启动中');
    case 'RUNNING':
      return t('ai.runtime.status.running', '运行中');
    case 'STOPPING':
      return t('ai.runtime.status.stopping', '停止中');
    case 'SUCCEEDED':
      return t('ai.runtime.status.succeeded', '已完成');
    case 'FAILED':
      return t('ai.runtime.status.failed', '已失败');
    case 'LOST':
      return t('ai.runtime.status.lost', '已失联');
    case 'CANCELLED':
      return t('ai.runtime.status.cancelled', '已取消');
    default:
      return t('ai.runtime.status.unknown', '状态未知');
  }
};

export const runtimeNetworkModeLabel = (
  t: Translate,
  networkMode?: string,
) => {
  switch (networkMode) {
    case 'none':
      return t('ai.runtime.network.none', '禁止网络');
    case 'bridge':
      return t('ai.runtime.network.bridge', 'Bridge 网络');
    case 'egress':
      return t('ai.runtime.network.egress', '受控出口');
    default:
      return t('ai.runtime.network.unknown', '网络模式未知');
  }
};

export const runtimeErrorLabel = (
  t: Translate,
  errorCode?: string,
) => {
  switch (errorCode) {
    case 'AI_RUNTIME_NODE_SHUT_DOWN':
      return t('ai.runtime.error.nodeShutDown', 'Runtime Node 已正常关闭');
    case 'AI_RUNTIME_NODE_HEARTBEAT_EXPIRED':
      return t('ai.runtime.error.nodeHeartbeatExpired', 'Runtime Node 心跳已超时');
    case 'AI_RUNTIME_WORKLOAD_CANCELLATION_REQUESTED':
      return t('ai.runtime.error.workloadCancellationRequested', '工作负载正在取消');
    case 'AI_RUNTIME_PROFILE_REVISION_CHANGED':
      return t('ai.runtime.error.profileRevisionChanged', 'Runtime Profile 版本已更新');
    case 'AI_RUNTIME_POOL_SCALE_DOWN_REQUESTED':
      return t('ai.runtime.error.poolScaleDownRequested', 'Runtime Pool 正在缩容');
    case 'AI_RUNTIME_WORKLOAD_DEADLINE_EXPIRED':
      return t('ai.runtime.error.workloadDeadlineExpired', '工作负载执行已超时');
    case 'AI_RUNTIME_WORKLOAD_NOT_OBSERVABLE':
      return t('ai.runtime.error.workloadNotObservable', '暂时无法获取工作负载状态');
    case 'AI_RUNTIME_IMAGE_RESOLUTION_FAILED':
      return t('ai.runtime.error.imageResolutionFailed', 'OCI 镜像校验或解析失败');
    case 'AI_RUNTIME_OPERATION_FAILED':
      return t('ai.runtime.error.operationFailed', 'Runtime 操作失败，请查看运行配置后重试');
    default:
      return t('ai.runtime.error.unknown', 'Runtime 状态异常');
  }
};
