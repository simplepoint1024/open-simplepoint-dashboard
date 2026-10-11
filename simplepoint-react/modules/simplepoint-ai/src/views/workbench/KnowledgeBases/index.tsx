import api from '@/api';
import type {TableButtonProps} from '@simplepoint/components/Table';
import SimpleTable from '@simplepoint/components/SimpleTable';
import DataTable from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import {request, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {del, get, post, useData} from '@simplepoint/shared/api/methods';
import {normalizePage, type Page, type RawPage} from '@simplepoint/shared/types/request';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {useQueryClient} from '@tanstack/react-query';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {hasRecordIdentity} from '@simplepoint/components/SimpleTable/recordIdentity';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePagePolling} from '@simplepoint/shared/page/usePagePolling';
import {
  Alert,
  App,
  Modal,
  Tag,
  Typography,
  Upload,
} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import type {UploadFile} from 'antd/es/upload/interface';
import {DeleteOutlined, InboxOutlined, ReloadOutlined} from '@simplepoint/shared/types/icon';
import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {
  knowledgeDocumentErrorLabel,
  knowledgeDocumentSourceLabel,
  knowledgeDocumentStatusLabel,
  knowledgeRetrievalModeLabel,
} from './labels';
import {localizeWorkbenchOperationError} from '../workbenchErrorCodes';

const {Paragraph, Text} = Typography;

type KnowledgeBaseRow = {
  id: string;
  name?: string;
  code?: string;
  retrievalMode?: RetrievalMode;
  enabled?: boolean;
  documentCount?: number;
  chunkCount?: number;
};

type KnowledgeDocument = {
  id: string;
  name?: string;
  fileName?: string;
  mimeType?: string;
  fileSize?: number;
  sourceType?: string;
  status?: string;
  chunkCount?: number;
  processedAt?: string;
  errorMessage?: string;
};

type RetrievalMode = 'VECTOR' | 'KEYWORD' | 'HYBRID';

type SearchHit = {
  chunkId: string;
  documentId: string;
  documentName?: string;
  chunkIndex?: number;
  content?: string;
  score?: number;
  vectorScore?: number;
  keywordScore?: number;
};

type RetrievalResult = {
  query: string;
  mode: RetrievalMode;
  hits: SearchHit[];
};

const emptyDocuments: Page<KnowledgeDocument> = {
  content: [],
  page: {number: 0, size: 20, totalElements: 0, totalPages: 0},
};

const formatBytes = (bytes?: unknown) => {
  if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes < 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
};

const scoreText = (value?: unknown) => typeof value === 'number' && Number.isFinite(value) ? value.toFixed(4) : '—';

const KnowledgeBasesSession = () => {
  const baseConfig = api['ai-workbench.knowledge-bases'];
  const {t, ensure, locale} = useI18n();
  const {message} = App.useApp();
  const operation = useOperationScope();
  const scope = useQueryScope();
  const queryClient = useQueryClient();
  const [selectedKnowledgeBase, setSelectedKnowledgeBase] = useState<KnowledgeBaseRow | null>(null);
  const [documentsOpen, setDocumentsOpen] = useState(false);
  const [documentPage, setDocumentPage] = useState(0);
  const [documentSize, setDocumentSize] = useState(20);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploadFiles, setUploadFiles] = useState<UploadFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const [textOpen, setTextOpen] = useState(false);
  const [textSaving, setTextSaving] = useState(false);
  const textForm = useRef<SFormHandle>(null);
  const [textValues, setTextValues] = useState<Record<string, unknown>>({});
  const [retrieveOpen, setRetrieveOpen] = useState(false);
  const [retrieving, setRetrieving] = useState(false);
  const [retrieveValues, setRetrieveValues] = useState<Record<string, unknown>>({});
  const [retrievalResult, setRetrievalResult] = useState<RetrievalResult | null>(null);
  const [deleting, setDeleting] = useState<KnowledgeDocument>();
  const [writeError, setWriteError] = useState('');
  const [textDirty, setTextDirty] = useState(false);
  const [discard, setDiscard] = useState<'text' | 'upload'>();
  const permissions = useSchema(baseConfig.baseUrl, {retry: false, staleTime: 30_000});
  const canDocuments = !permissions.error && Boolean(permissions.data?.buttons.some(button => button.key === 'documents'));
  const canRetrieve = !permissions.error && Boolean(permissions.data?.buttons.some(button => button.key === 'retrieve'));
  const documentsLocked = operation.busy || uploadOpen || textOpen || !!deleting;
  const locked = documentsOpen || retrieveOpen || documentsLocked;
  usePageRetention(!locked, documentsLocked || retrieveOpen);

  const tableErrorMessageResolver = useCallback((error: unknown) => (
    localizeWorkbenchOperationError(t, error, {
      key: 'ai.error.operationFailed',
      fallback: '知识库操作失败，请稍后重试',
    })
  ), [t]);

  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [baseConfig.i18nNamespaces, ensure, locale]);

  const documentsQuery = useData(['knowledge-documents', baseConfig.baseUrl, selectedKnowledgeBase?.id, documentPage, documentSize],
    async ({signal}) => normalizePage(await get<RawPage<KnowledgeDocument>>(
      `${baseConfig.baseUrl}/${encodeURIComponent(selectedKnowledgeBase!.id)}/documents`,
      {page: documentPage, size: documentSize, sort: 'createdAt,desc'}, {signal})),
    {enabled: documentsOpen && Boolean(selectedKnowledgeBase?.id), retry: false});
  const documents = documentsQuery.data ?? emptyDocuments;
  const documentsLoading = documentsQuery.isFetching;
  const limitsQuery = useData(['knowledge-document-limits', baseConfig.baseUrl],
    ({signal}) => get<{maxUploadBytes: number}>(`${baseConfig.baseUrl}/document-limits`, undefined, {signal}),
    {enabled: documentsOpen && canDocuments, retry: false, staleTime: 30_000});
  const maxUploadBytes = limitsQuery.data?.maxUploadBytes;
  const hasUploadLimit = typeof maxUploadBytes === 'number' && Number.isSafeInteger(maxUploadBytes) && maxUploadBytes > 0;
  const uploadLimitText = t('ai.knowledge-bases.documents.maxSizeConfigured', '单个文档最大 {size}', {size: formatBytes(maxUploadBytes)});
  useEffect(() => {
    const count = documentsQuery.data?.page.totalPages;
    if (!documentsQuery.error && count != null && documentPage > 0 && documentPage >= count) setDocumentPage(Math.max(0, count - 1));
  }, [documentsQuery.data?.page.totalPages, documentsQuery.error, documentPage]);

  const refreshKnowledgeBases = useCallback(() => {
    // Refresh only this scope's list pages; polling must not remount the table or reload its preferences/schema.
    void queryClient.invalidateQueries({queryKey: scopedQueryKey(scope, [baseConfig.baseUrl, baseConfig.name, ''])});
  }, [queryClient, scope, baseConfig.baseUrl, baseConfig.name]);

  const loadDocuments = useCallback(async (_knowledgeBase?: KnowledgeBaseRow, _silent = false) => {
    await documentsQuery.refetch();
  }, [documentsQuery.refetch]);

  const hasActiveIndexJobs = useMemo(
    () => documents.content.some((document) => (
      document.status === 'PENDING'
      || document.status === 'PROCESSING'
      || document.status === 'REINDEXING'
    )),
    [documents.content],
  );

  const pollDocuments = useCallback(async () => {
    if (!selectedKnowledgeBase) return;
    await loadDocuments(selectedKnowledgeBase, true);
    refreshKnowledgeBases();
  }, [loadDocuments, refreshKnowledgeBases, selectedKnowledgeBase]);
  usePagePolling(pollDocuments, documentsOpen && !!selectedKnowledgeBase && hasActiveIndexJobs && !documentsLocked, 2500);

  const openDocuments = useCallback((rows: KnowledgeBaseRow[]) => {
    const knowledgeBase = rows?.[0];
    if (locked || operation.isBusy() || !canDocuments || !operation.isSelected() || !hasRecordIdentity(knowledgeBase)) return;
    setSelectedKnowledgeBase(structuredClone(knowledgeBase)); setWriteError('');
    setDocumentPage(0);
    setDocumentsOpen(true);
  }, [locked, canDocuments, operation.isSelected, operation.isBusy]);

  const openRetrieve = useCallback((rows: KnowledgeBaseRow[]) => {
    const knowledgeBase = rows?.[0];
    if (locked || operation.isBusy() || !canRetrieve || !operation.isSelected() || !hasRecordIdentity(knowledgeBase)) return;
    setSelectedKnowledgeBase(structuredClone(knowledgeBase)); setWriteError('');
    setRetrievalResult(null);
    setRetrieveValues({
      query: '',
      mode: knowledgeBase.retrievalMode || 'HYBRID',
      topK: 10,
      scoreThreshold: 0,
    });
    setRetrieveOpen(true);
  }, [locked, canRetrieve, operation.isSelected, operation.isBusy]);

  const closeDraft = (kind: 'text' | 'upload', confirmed = false) => {
    if (operation.isBusy() || !operation.isSelected()) return;
    if (!confirmed && (kind === 'text' ? textDirty : uploadFiles.length > 0)) {setDiscard(kind); return;}
    setDiscard(undefined); setWriteError('');
    if (kind === 'text') {setTextOpen(false); setTextValues({}); setTextDirty(false);}
    else {setUploadOpen(false); setUploadFiles([]);}
  };

  const handleUpload = useCallback(async () => {
    if (!uploadOpen || !canDocuments || documentsQuery.error || documentsLoading || discard || !hasUploadLimit || limitsQuery.error) return;
    const target = uploadFiles[0]?.originFileObj as File | undefined;
    if (!selectedKnowledgeBase || !target) {
      message.warning(t('ai.knowledge-bases.warning.selectFile', '请选择要上传的文档'));
      return;
    }
    if (target.size > maxUploadBytes!) {
      setWriteError(uploadLimitText); return;
    }
    const formData = new FormData();
    formData.append('file', target);
    const controller = operation.begin(); if (!controller) return; setWriteError(''); setUploading(true);
    try {
      await request<KnowledgeDocument>(
        `${baseConfig.baseUrl}/${encodeURIComponent(selectedKnowledgeBase.id)}/documents/upload`,
        {method: 'POST', body: formData, signal: controller.signal, notifyOnError: false},
      );
      if (!operation.current(controller.signal)) return;
      message.success(t('ai.knowledge-bases.success.upload', '文档已进入索引队列'));
      setUploadOpen(false);
      setUploadFiles([]);
      if (documentPage !== 0) setDocumentPage(0); else await loadDocuments(selectedKnowledgeBase);
      refreshKnowledgeBases();
    } catch (error) {
      if (operation.current(controller.signal)) setWriteError(resolveApiErrorMessage(
        error,
        t('ai.knowledge-bases.error.upload', '文档上传失败，请检查文件后重试'),
      ));
    } finally {
      operation.finish(controller); if (operation.current()) setUploading(false);
    }
  }, [baseConfig.baseUrl, loadDocuments, message, refreshKnowledgeBases, selectedKnowledgeBase, t, uploadFiles,
    uploadOpen, canDocuments, documentsQuery.error, documentsLoading, discard, documentPage, operation.begin, operation.current, operation.finish,
    hasUploadLimit, limitsQuery.error, maxUploadBytes, uploadLimitText]);

  const handleAddText = useCallback(async (values: Record<string, unknown>) => {
    if (!selectedKnowledgeBase || !textOpen || !canDocuments || documentsQuery.error || documentsLoading || discard) return;
    const controller = operation.begin(); if (!controller) return; setWriteError('');
    setTextSaving(true);
    try {
      await post<KnowledgeDocument>(
        `${baseConfig.baseUrl}/${encodeURIComponent(selectedKnowledgeBase.id)}/documents/text`,
        values,
        {signal: controller.signal, notifyOnError: false},
      );
      if (!operation.current(controller.signal)) return;
      message.success(t('ai.knowledge-bases.success.text', '文本文档已进入索引队列'));
      setTextOpen(false);
      setTextValues({});
      setTextDirty(false);
      if (documentPage !== 0) setDocumentPage(0); else await loadDocuments(selectedKnowledgeBase);
      refreshKnowledgeBases();
    } catch (error) {
      if (operation.current(controller.signal)) setWriteError(resolveApiErrorMessage(
        error,
        t('ai.knowledge-bases.error.addText', '文本文档新增失败，请检查内容后重试'),
      ));
    } finally {
      operation.finish(controller); if (operation.current()) setTextSaving(false);
    }
  }, [baseConfig.baseUrl, loadDocuments, message, refreshKnowledgeBases, selectedKnowledgeBase, t,
    textOpen, canDocuments, documentsQuery.error, documentsLoading, discard, documentPage, operation.begin, operation.current, operation.finish]);

  const handleReindex = useCallback(async (document: KnowledgeDocument) => {
    if (!selectedKnowledgeBase || documentsLocked || !canDocuments || documentsQuery.error || documentsLoading || !hasRecordIdentity(document)) return;
    const controller = operation.begin(); if (!controller) return; setWriteError('');
    try {
      await post<KnowledgeDocument>(
        `${baseConfig.baseUrl}/${encodeURIComponent(selectedKnowledgeBase.id)}/documents/${encodeURIComponent(document.id)}/reindex`,
        {},
        {signal: controller.signal, notifyOnError: false},
      );
      if (!operation.current(controller.signal)) return;
      message.success(t('ai.knowledge-bases.success.reindex', '重新索引任务已提交'));
      await loadDocuments(selectedKnowledgeBase);
      refreshKnowledgeBases();
    } catch (error) {
      if (operation.current(controller.signal)) setWriteError(resolveApiErrorMessage(
        error,
        t('ai.knowledge-bases.error.reindex', '重新索引失败，请刷新后重试'),
      ));
    } finally {operation.finish(controller);}
  }, [baseConfig.baseUrl, loadDocuments, message, refreshKnowledgeBases, selectedKnowledgeBase, t,
    documentsLocked, canDocuments, documentsQuery.error, documentsLoading, operation.begin, operation.current, operation.finish]);

  const handleDeleteDocument = useCallback(async (document: KnowledgeDocument) => {
    if (!selectedKnowledgeBase || deleting?.id !== document.id || !canDocuments || documentsQuery.error || documentsLoading || !hasRecordIdentity(document)) return;
    const controller = operation.begin(); if (!controller) return; setWriteError('');
    try {
      await del(`${baseConfig.baseUrl}/${encodeURIComponent(selectedKnowledgeBase.id)}/documents`, document.id, {signal: controller.signal, notifyOnError: false});
      if (!operation.current(controller.signal)) return;
      setDeleting(undefined);
      message.success(t('table.deleteSuccess', '删除成功'));
      await loadDocuments(selectedKnowledgeBase);
      refreshKnowledgeBases();
    } catch (error) {
      if (operation.current(controller.signal)) setWriteError(resolveApiErrorMessage(
        error,
        t('ai.knowledge-bases.error.deleteDocument', '文档删除失败，请刷新后重试'),
      ));
    } finally {operation.finish(controller);}
  }, [baseConfig.baseUrl, loadDocuments, message, refreshKnowledgeBases, selectedKnowledgeBase, t,
    deleting, canDocuments, documentsQuery.error, documentsLoading, operation.begin, operation.current, operation.finish]);

  const handleRetrieve = useCallback(async (values: Record<string, unknown>) => {
    if (!selectedKnowledgeBase || !retrieveOpen || !canRetrieve) return;
    const controller = operation.begin(); if (!controller) return; setWriteError('');
    setRetrieving(true);
    try {
      const result = await post<RetrievalResult>(
        `${baseConfig.baseUrl}/${encodeURIComponent(selectedKnowledgeBase.id)}/retrieve`,
        values,
        {signal: controller.signal, notifyOnError: false},
      );
      if (!operation.current(controller.signal)) return;
      setRetrievalResult(result);
      if (!result.hits?.length) {
        message.info(t('ai.knowledge-bases.retrieve.empty', '没有找到达到相关度要求的内容'));
      }
    } catch (error) {
      if (operation.current(controller.signal)) setWriteError(resolveApiErrorMessage(
        error,
        t('ai.knowledge-bases.error.retrieve', '知识检索失败，请检查查询条件后重试'),
      ));
    } finally {
      operation.finish(controller); if (operation.current()) setRetrieving(false);
    }
  }, [baseConfig.baseUrl, message, selectedKnowledgeBase, t, retrieveOpen, canRetrieve,
    operation.begin, operation.current, operation.finish]);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema?.properties ?? {};
    if (properties.retrievalMode) {
      properties.retrievalMode.oneOf = [
        {const: 'HYBRID', title: t('ai.knowledge-bases.mode.HYBRID', '混合检索')},
        {const: 'VECTOR', title: t('ai.knowledge-bases.mode.VECTOR', '向量检索')},
        {const: 'KEYWORD', title: t('ai.knowledge-bases.mode.KEYWORD', '关键词检索')},
      ];
    }
    delete properties.scopeType;
    delete properties.tenantId;
    delete properties.documentCount;
    delete properties.chunkCount;
    return nextSchema;
  }, [t]);
  const formUiSchema = useMemo(() => ({embeddingModelId: {
    'ui:widget': 'EntitySelect', 'ui:options': {endpoint: `${baseConfig.baseUrl}/embedding-model-options`},
  }}), [baseConfig.baseUrl]);

  const columnOverrides = useMemo(() => ({
    retrievalMode: {
      width: 120,
      render: (value: RetrievalMode) => knowledgeRetrievalModeLabel(t, value),
    },
    enabled: {
      width: 100,
      render: (value: boolean) => (
        <Tag color={value === true ? 'green' : 'default'}>
          {value === true ? t('ai.common.enabled', '已启用') : value === false ? t('ai.common.disabled', '已禁用') : '—'}
        </Tag>
      ),
    },
    documentCount: {width: 100},
    chunkCount: {width: 100},
  }), [t]);

  const documentColumns: ColumnsType<KnowledgeDocument> = [
    {title: t('ai.knowledge-documents.title.name', '文档名称'), dataIndex: 'name', ellipsis: true},
    {
      title: t('ai.knowledge-documents.title.sourceType', '来源'),
      dataIndex: 'sourceType',
      width: 90,
      render: (value: string) => knowledgeDocumentSourceLabel(t, value),
    },
    {title: t('ai.knowledge-documents.title.fileSize', '大小'), dataIndex: 'fileSize', width: 100, render: formatBytes},
    {
      title: t('ai.knowledge-documents.title.status', '状态'),
      dataIndex: 'status',
      width: 100,
      render: (value, record) => (
        <Tag
          color={value === 'READY'
            ? 'green'
            : value === 'FAILED'
              ? 'red'
              : value === 'REINDEX_FAILED'
                ? 'orange'
                : ['PENDING', 'PROCESSING', 'REINDEXING'].includes(value) ? 'blue' : 'default'}
          title={record.errorMessage
            ? knowledgeDocumentErrorLabel(t, record.errorMessage)
            : undefined}
        >
          {knowledgeDocumentStatusLabel(t, value)}
        </Tag>
      ),
    },
    {title: t('ai.knowledge-documents.title.chunkCount', '分块数'), dataIndex: 'chunkCount', width: 90},
    {
      title: t('table.action', '操作'),
      key: 'actions',
      width: 130,
      // The server schema's documents action authorizes this entire management dialog.
      render: (_, record) => <TableActions selectedKeys={[record.id]} selectedRows={[record]} buttons={[
        {key: 'reindex', sort: 10, type: 'link', size: 'small', icon: <ReloadOutlined/>,
          text: t('ai.knowledge-bases.button.reindex', '重建'),
          disabled: documentsLocked || !canDocuments || documentsLoading || !!documentsQuery.error || !hasRecordIdentity(record)
            || ['PENDING', 'PROCESSING', 'REINDEXING'].includes(record.status ?? ''),
          onClick: () => void handleReindex(record)},
        {key: 'delete', sort: 20, type: 'link', size: 'small', icon: <DeleteOutlined/>, danger: true,
          text: t('table.button.delete', '删除'), iconOnly: true,
          disabled: documentsLocked || !canDocuments || documentsLoading || !!documentsQuery.error || !hasRecordIdentity(record),
          onClick: () => {
            if (documentsLocked || operation.isBusy() || !canDocuments || documentsLoading || documentsQuery.error || !operation.isSelected() || !hasRecordIdentity(record)) return;
            setWriteError(''); setDeleting(structuredClone(record));
          }},
      ]}/>,
    },
  ];

  const hitColumns: ColumnsType<SearchHit> = [
    {title: t('ai.knowledge-bases.retrieve.document', '文档'), dataIndex: 'documentName', width: 180, ellipsis: true},
    {title: t('ai.knowledge-bases.retrieve.score', '综合分'), dataIndex: 'score', width: 90, render: scoreText},
    {title: t('ai.knowledge-bases.retrieve.vectorScore', '向量分'), dataIndex: 'vectorScore', width: 90, render: scoreText},
    {title: t('ai.knowledge-bases.retrieve.keywordScore', '关键词分'), dataIndex: 'keywordScore', width: 90, render: scoreText},
    {
      title: t('ai.knowledge-bases.retrieve.content', '命中内容'),
      dataIndex: 'content',
      render: (value: string) => <Paragraph ellipsis={{rows: 4, expandable: true}} style={{marginBottom: 0}}>{value}</Paragraph>,
    },
  ];

  const customButtonEvents: Record<string, (
    selectedRowKeys: React.Key[],
    selectedRows: KnowledgeBaseRow[],
    props: TableButtonProps,
  ) => void> = {
    documents: (_keys, rows) => openDocuments(rows),
    retrieve: (_keys, rows) => openRetrieve(rows),
  };

  const documentReadError = documentsQuery.isError && <Alert type="error" showIcon
    title={t('ai.knowledge-bases.error.loadDocuments', '文档列表加载失败')}
    action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retryDocuments', sort: 10,
      text: t('table.retry', '重试'), disabled: operation.busy || documentsLoading,
      onClick: () => {if (!operation.isBusy() && operation.isSelected()) void loadDocuments();}}]}/>}/>;
  const uploadLimitsError = limitsQuery.isError && <Alert type="error" showIcon title={t('table.loadFail', '加载失败')}
    action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retryLimits', sort: 10,
      text: t('table.retry', '重试'), disabled: operation.busy || limitsQuery.isFetching,
      onClick: () => {if (!operation.isBusy() && operation.isSelected()) void limitsQuery.refetch();}}]}/>}/>;

  return (
    <>
      <SimpleTable
        {...baseConfig}
        customButtonEvents={customButtonEvents}
        errorMessageResolver={tableErrorMessageResolver}
        formSchemaTransform={formSchemaTransform}
        formUiSchema={formUiSchema}
        isButtonDisabled={() => locked || !operation.selected}
        columnOverrides={columnOverrides}
        initialValues={{
          retrievalMode: 'HYBRID',
          chunkSize: 1000,
          chunkOverlap: 150,
          topK: 10,
          scoreThreshold: 0,
          vectorWeight: 0.7,
          keywordWeight: 0.3,
          enabled: true,
        }}
      />

      <Modal
        wrapClassName="knowledge-documents"
        width={1100}
        open={documentsOpen && operation.selected}
        destroyOnHidden={!documentsOpen}
        title={`${selectedKnowledgeBase?.name || ''} · ${t('ai.knowledge-bases.documents.title', '文档管理')}`}
        footer={null}
        closable={!documentsLocked} maskClosable={!documentsLocked} keyboard={!documentsLocked}
        onCancel={() => {if (!documentsLocked && !operation.isBusy()) {setDocumentsOpen(false); setWriteError('');}}}
      >
        <Text type="secondary">
            {t('ai.knowledge-bases.documents.supported', '支持 PDF、Office、OpenDocument、TXT、Markdown、CSV、JSON、XML、HTML、RTF 和 EPUB')}
        </Text>
        {documentReadError}
        {uploadLimitsError}
        {writeError && !uploadOpen && !textOpen && !deleting && <Alert type="error" showIcon title={writeError}/>}
        <DataTable
          rowKey="id"
          size="small"
          loading={documentsLoading}
          columns={documentColumns}
          dataSource={documents.content}
          refresh={documentsLocked ? undefined : () => void loadDocuments()}
          toolbarExtra={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
            {key: 'upload', sort: 10, type: 'primary', text: t('ai.knowledge-bases.documents.upload', '上传文档'),
              disabled: documentsLocked || !canDocuments || documentsLoading || !!documentsQuery.error || !hasUploadLimit || !!limitsQuery.error,
              onClick: () => {if (documentsLocked || operation.isBusy() || !canDocuments || documentsLoading || documentsQuery.error || !hasUploadLimit || limitsQuery.error || !operation.isSelected()) return;
                setWriteError(''); setUploadFiles([]); setUploadOpen(true);}},
            {key: 'addText', sort: 20, text: t('ai.knowledge-bases.documents.addText', '新增文本'),
              disabled: documentsLocked || !canDocuments || documentsLoading || !!documentsQuery.error,
              onClick: () => {if (documentsLocked || operation.isBusy() || !canDocuments || documentsLoading || documentsQuery.error || !operation.isSelected()) return;
                setWriteError(''); setTextValues({}); setTextDirty(false); setTextOpen(true);}},
          ]}/>}
          pagination={{current: documentPage + 1, pageSize: documentSize,
            total: documents.page.totalElements, showSizeChanger: true,
            disabled: documentsLocked,
            onChange: (page, size) => {if (!documentsLocked && !operation.isBusy()) {setDocumentPage(page - 1); setDocumentSize(size);}}}}
        />
      </Modal>

      <Modal
        wrapClassName="knowledge-upload"
        open={uploadOpen && operation.selected}
        destroyOnHidden={!uploadOpen}
        title={t('ai.knowledge-bases.documents.upload', '上传文档')}
        confirmLoading={uploading}
        onOk={() => void handleUpload()}
        closable={!uploading} maskClosable={!uploading} keyboard={!uploading}
        onCancel={() => closeDraft('upload')}
        cancelButtonProps={{disabled: uploading}}
        okButtonProps={{disabled: !canDocuments || !!documentsQuery.error || documentsLoading || uploadFiles.length !== 1 || !!discard || !hasUploadLimit || !!limitsQuery.error}}
      >
        {documentReadError}
        {uploadLimitsError}
        {writeError && <Alert type="error" showIcon title={writeError}/>}
        <Upload.Dragger
          beforeUpload={() => false}
          maxCount={1}
          fileList={uploadFiles}
          disabled={uploading || !canDocuments || !!discard}
          onChange={({fileList}) => {if (!operation.isBusy() && operation.isSelected() && !discard) {setUploadFiles(fileList.slice(-1)); setWriteError('');}}}
        >
          <p className="ant-upload-drag-icon"><InboxOutlined/></p>
          <p>{t('ai.knowledge-bases.documents.drop', '点击或拖拽文档到此处')}</p>
          <p className="ant-upload-hint">{hasUploadLimit ? uploadLimitText : '—'}</p>
        </Upload.Dragger>
      </Modal>

      <Modal
        wrapClassName="knowledge-text"
        open={textOpen && operation.selected}
        destroyOnHidden={!textOpen}
        title={t('ai.knowledge-bases.documents.addText', '新增文本')}
        confirmLoading={textSaving}
        onOk={() => textForm.current?.submit()}
        closable={!textSaving} maskClosable={!textSaving} keyboard={!textSaving}
        onCancel={() => closeDraft('text')}
        cancelButtonProps={{disabled: textSaving}}
        okButtonProps={{disabled: !canDocuments || !!documentsQuery.error || documentsLoading || !!discard}}
      >
        {documentReadError}
        {writeError && <Alert type="error" showIcon title={writeError}/>}
        <SForm ref={textForm} hideSubmit disabled={textSaving || !canDocuments || !!discard} schema={{type: 'object', required: ['name', 'content'], properties: {
          name: {type: 'string', pattern: '\\S', maxLength: 512, title: t('ai.knowledge-documents.title.name', '文档名称')},
          content: {type: 'string', pattern: '\\S', title: t('ai.knowledge-bases.documents.content', '文本内容')},
        }}} uiSchema={{content: {'ui:widget': 'textarea', 'ui:options': {autoSize: {minRows: 12, maxRows: 16}}}}}
          formData={textValues} onChange={({formData}) => {if (!operation.isBusy() && operation.isSelected() && !discard) {setTextValues(formData ?? {}); setTextDirty(true);}}}
          onSubmit={({formData}) => void handleAddText(formData ?? {})}/>
      </Modal>

      <Modal
        wrapClassName="knowledge-retrieve"
        width={1100}
        open={retrieveOpen && operation.selected}
        destroyOnHidden={!retrieveOpen}
        title={`${selectedKnowledgeBase?.name || ''} · ${t('ai.knowledge-bases.retrieve.title', '检索测试')}`}
        footer={null}
        closable={!retrieving} maskClosable={!retrieving} keyboard={!retrieving}
        onCancel={() => {if (!operation.isBusy()) {setRetrieveOpen(false); setWriteError('');}}}
      >
        <Alert
          type="info"
          closable
          showIcon
          style={{marginBottom: 16}}
          message={t('ai.knowledge-bases.retrieve.tip', '可临时覆盖检索模式、Top K 和最低相关度，不会修改知识库配置。')}
        />
        {writeError && <Alert type="error" showIcon title={writeError}/>}
        <SForm disabled={retrieving || !canRetrieve} submitDisabled={!canRetrieve} submitLoading={retrieving} schema={{type: 'object', required: ['query', 'mode', 'topK', 'scoreThreshold'], properties: {
          query: {type: 'string', pattern: '\\S', title: t('ai.knowledge-bases.retrieve.placeholder', '输入要检索的问题')},
          mode: {type: 'string', title: t('ai.knowledge-bases.retrieve.mode', '检索模式'), oneOf: [
            {const: 'HYBRID', title: t('ai.knowledge-bases.mode.HYBRID', '混合检索')},
            {const: 'VECTOR', title: t('ai.knowledge-bases.mode.VECTOR', '向量检索')},
            {const: 'KEYWORD', title: t('ai.knowledge-bases.mode.KEYWORD', '关键词检索')},
          ]},
          topK: {type: 'integer', minimum: 1, maximum: 100, title: 'Top K'},
          scoreThreshold: {type: 'number', minimum: 0, maximum: 1, title: t('ai.knowledge-bases.retrieve.threshold', '阈值')},
        }}} uiSchema={{'ui:submitButtonOptions': {submitText: t('ai.knowledge-bases.button.retrieve', '检索')}}}
          formData={retrieveValues} onChange={({formData}) => {if (!operation.isBusy() && operation.isSelected()) setRetrieveValues(formData ?? {});}}
          onSubmit={({formData}) => void handleRetrieve(formData ?? {})}/>
        <DataTable
          rowKey="chunkId"
          size="small"
          loading={retrieving}
          columns={hitColumns}
          dataSource={retrievalResult?.hits || []}
          pagination={false}
          scroll={{y: 520}}
        />
      </Modal>
      <Modal wrapClassName="knowledge-document-delete" open={!!deleting && operation.selected} destroyOnHidden={!deleting}
        title={t('table.button.delete', '删除')} confirmLoading={operation.busy}
        closable={!operation.busy} maskClosable={!operation.busy} keyboard={!operation.busy}
        cancelButtonProps={{disabled: operation.busy}} okButtonProps={{danger: true, disabled: !canDocuments || !!documentsQuery.error || documentsLoading}}
        onCancel={() => {if (!operation.isBusy()) {setDeleting(undefined); setWriteError('');}}}
        onOk={() => {if (deleting) void handleDeleteDocument(deleting);}}>
        <Paragraph>{deleting?.name || deleting?.fileName || deleting?.id}</Paragraph>
        {documentReadError}
        {writeError && <Alert type="error" showIcon title={writeError}/>}
      </Modal>
      <Modal wrapClassName="knowledge-discard-draft" open={!!discard && operation.selected} destroyOnHidden={!discard}
        title={t('personal.discard', '放弃未保存的修改？')}
        onCancel={() => setDiscard(undefined)} footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'continue', sort: 1, text: t('personal.continueEditing', '继续编辑'), onClick: () => setDiscard(undefined)},
          {key: 'discard', sort: 2, danger: true, type: 'primary', text: t('personal.discardConfirm', '放弃修改'),
            onClick: () => {if (discard) closeDraft(discard, true);}},
        ]}/>}/>
    </>
  );
};

export default function KnowledgeBases() {
  const scope = useQueryScope();
  return <KnowledgeBasesSession key={JSON.stringify(scope)}/>;
}
