type Translate = (key: string, fallback?: string) => string;

export type InvocationTag = {
  label: string;
  color: string;
};

export const invocationStatusTag = (
  t: Translate,
  status?: string,
): InvocationTag => {
  switch (status) {
    case 'SUCCEEDED':
      return {
        label: t('ai.invocations.status.SUCCEEDED', '成功'),
        color: 'green',
      };
    case 'FAILED':
      return {
        label: t('ai.invocations.status.FAILED', '失败'),
        color: 'red',
      };
    case 'RUNNING':
      return {
        label: t('ai.invocations.status.RUNNING', '执行中'),
        color: 'blue',
      };
    case 'CANCELLED':
      return {
        label: t('ai.invocations.status.CANCELLED', '已取消'),
        color: 'default',
      };
    default:
      return {
        label: t('ai.invocations.status.UNKNOWN', '未知调用状态'),
        color: 'default',
      };
  }
};

export const invocationOperationLabel = (
  t: Translate,
  operation?: string,
): string => {
  switch (operation) {
    case 'EMBEDDING':
      return t('ai.invocations.operation.EMBEDDING', '向量嵌入');
    case 'GENERATION':
      return t('ai.invocations.operation.GENERATION', '内容生成');
    case 'RERANK':
      return t('ai.invocations.operation.RERANK', '重排序');
    default:
      return t('ai.invocations.operation.UNKNOWN', '未知调用类型');
  }
};

export const invocationBillingStatusTag = (
  t: Translate,
  status?: string,
): InvocationTag => {
  switch (status) {
    case 'CALCULATED':
      return {
        label: t('ai.invocations.billingStatus.CALCULATED', '已计费'),
        color: 'gold',
      };
    case 'UNPRICED':
      return {
        label: t('ai.invocations.billingStatus.UNPRICED', '未配置价格'),
        color: 'orange',
      };
    case 'NOT_CHARGED':
      return {
        label: t('ai.invocations.billingStatus.NOT_CHARGED', '不计费'),
        color: 'default',
      };
    case 'PENDING':
      return {
        label: t('ai.invocations.billingStatus.PENDING', '待计算'),
        color: 'blue',
      };
    default:
      return {
        label: t('ai.invocations.billingStatus.UNKNOWN', '未知计费状态'),
        color: 'default',
      };
  }
};
