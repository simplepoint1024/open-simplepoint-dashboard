type Translate = (key: string, fallback?: string) => string;

export const knowledgeRetrievalModeLabel = (t: Translate, mode?: string) => {
  switch (mode) {
    case 'HYBRID':
      return t('ai.knowledge-bases.mode.HYBRID', '混合检索');
    case 'VECTOR':
      return t('ai.knowledge-bases.mode.VECTOR', '向量检索');
    case 'KEYWORD':
      return t('ai.knowledge-bases.mode.KEYWORD', '关键词检索');
    default:
      return t('ai.knowledge-bases.mode.UNKNOWN', '未知检索模式');
  }
};

export const knowledgeDocumentSourceLabel = (
  t: Translate,
  sourceType?: string,
) => {
  switch (sourceType) {
    case 'UPLOAD':
      return t('ai.knowledge-documents.source.UPLOAD', '文件上传');
    case 'TEXT':
      return t('ai.knowledge-documents.source.TEXT', '文本录入');
    default:
      return t('ai.knowledge-documents.source.UNKNOWN', '来源未知');
  }
};

export const knowledgeDocumentStatusLabel = (
  t: Translate,
  status?: string,
) => {
  switch (status) {
    case 'PENDING':
      return t('ai.knowledge-documents.status.PENDING', '等待处理');
    case 'PROCESSING':
      return t('ai.knowledge-documents.status.PROCESSING', '处理中');
    case 'READY':
      return t('ai.knowledge-documents.status.READY', '可检索');
    case 'FAILED':
      return t('ai.knowledge-documents.status.FAILED', '处理失败');
    case 'REINDEXING':
      return t('ai.knowledge-documents.status.REINDEXING', '重建中');
    case 'REINDEX_FAILED':
      return t('ai.knowledge-documents.status.REINDEX_FAILED', '重建失败');
    default:
      return t('ai.knowledge-documents.status.UNKNOWN', '状态未知');
  }
};

export const knowledgeDocumentErrorLabel = (
  t: Translate,
  errorCode?: string,
) => {
  switch (errorCode) {
    case 'AI_KNOWLEDGE_INDEX_FAILED':
      return t(
        'ai.knowledge-documents.error.indexFailed',
        '文档索引失败，请检查文档内容和嵌入模型配置后重试',
      );
    default:
      return t(
        'ai.knowledge-documents.error.unknown',
        '文档处理失败，请检查配置后重试',
      );
  }
};
