type Translate = (key: string, fallback?: string) => string;

export const modelTypeLabel = (
  t: Translate,
  modelType?: string,
) => {
  switch (modelType) {
    case 'LLM':
      return t('ai.models.type.LLM', '大语言模型');
    case 'EMBEDDING':
      return t('ai.models.type.EMBEDDING', '向量嵌入');
    case 'RERANK':
      return t('ai.models.type.RERANK', '重排序');
    case 'IMAGE':
      return t('ai.models.type.IMAGE', '图像');
    case 'AUDIO':
      return t('ai.models.type.AUDIO', '音频');
    case 'MODERATION':
      return t('ai.models.type.MODERATION', '内容审核');
    case 'MULTIMODAL':
      return t('ai.models.type.MULTIMODAL', '多模态');
    case 'OTHER':
      return t('ai.models.type.OTHER', '其他');
    default:
      return t('ai.models.type.UNKNOWN', '未知模型类型');
  }
};

export const resourceScopeLabel = (
  t: Translate,
  scopeType?: string,
) => {
  switch (scopeType) {
    case 'SYSTEM':
      return t('ai.scope.SYSTEM', '系统共享');
    case 'TENANT':
      return t('ai.scope.TENANT', '租户私有');
    default:
      return t('ai.scope.UNKNOWN', '归属范围未知');
  }
};
