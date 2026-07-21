import api from '@/api';
import type {TableButtonProps} from '@simplepoint/components/Table';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {request} from '@simplepoint/shared/api/client';
import {del, get, post} from '@simplepoint/shared/api/methods';
import type {Page} from '@simplepoint/shared/types/request';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  Upload,
  message,
} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import type {UploadFile} from 'antd/es/upload/interface';
import {DeleteOutlined, InboxOutlined, ReloadOutlined} from '@ant-design/icons';
import React, {useCallback, useEffect, useMemo, useState} from 'react';

const {Paragraph, Text} = Typography;
const {TextArea} = Input;

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

type EmbeddingModel = {
  id: string;
  modelId?: string;
  displayName?: string;
  providerName?: string;
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

type KnowledgeBaseViewProps = {
  configKey?: 'platform.ai-knowledge-bases' | 'tenant.ai-knowledge-bases';
};

const emptyDocuments: Page<KnowledgeDocument> = {
  content: [],
  page: {number: 0, size: 20, totalElements: 0, totalPages: 0},
};

const errorMessage = (error: unknown, fallback: string) => {
  const value = error as {userMessage?: string; message?: string};
  return value?.userMessage || value?.message || fallback;
};

const formatBytes = (bytes?: number) => {
  if (bytes == null) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
};

const scoreText = (value?: number) => value == null ? '-' : value.toFixed(4);

export const KnowledgeBaseView = ({
  configKey = 'platform.ai-knowledge-bases',
}: KnowledgeBaseViewProps) => {
  const baseConfig = api[configKey];
  const {t, ensure, locale} = useI18n();
  const [tableKey, setTableKey] = useState(0);
  const [embeddingModels, setEmbeddingModels] = useState<EmbeddingModel[]>([]);
  const [selectedKnowledgeBase, setSelectedKnowledgeBase] = useState<KnowledgeBaseRow | null>(null);
  const [documentsOpen, setDocumentsOpen] = useState(false);
  const [documents, setDocuments] = useState<Page<KnowledgeDocument>>(emptyDocuments);
  const [documentsLoading, setDocumentsLoading] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploadFiles, setUploadFiles] = useState<UploadFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const [textOpen, setTextOpen] = useState(false);
  const [textSaving, setTextSaving] = useState(false);
  const [textForm] = Form.useForm();
  const [retrieveOpen, setRetrieveOpen] = useState(false);
  const [retrieving, setRetrieving] = useState(false);
  const [retrieveForm] = Form.useForm();
  const [retrievalResult, setRetrievalResult] = useState<RetrievalResult | null>(null);

  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [baseConfig.i18nNamespaces, ensure, locale]);

  useEffect(() => {
    get<EmbeddingModel[]>(`${baseConfig.baseUrl}/embedding-models`)
      .then(setEmbeddingModels)
      .catch((error) => message.error(errorMessage(
        error,
        t('ai.knowledge-bases.error.loadModels', '可用 Embedding 模型加载失败'),
      )));
  }, [baseConfig.baseUrl, t]);

  const refreshKnowledgeBases = useCallback(() => {
    setTableKey((value) => value + 1);
  }, []);

  const loadDocuments = useCallback(async (knowledgeBase: KnowledgeBaseRow) => {
    setDocumentsLoading(true);
    try {
      const result = await get<Page<KnowledgeDocument>>(
        `${baseConfig.baseUrl}/${knowledgeBase.id}/documents`,
        {page: 0, size: 100, sort: 'createdAt,desc'},
      );
      setDocuments(result);
    } finally {
      setDocumentsLoading(false);
    }
  }, [baseConfig.baseUrl]);

  const openDocuments = useCallback((rows: KnowledgeBaseRow[]) => {
    const knowledgeBase = rows?.[0];
    if (!knowledgeBase?.id) return;
    setSelectedKnowledgeBase(knowledgeBase);
    setDocumentsOpen(true);
    void loadDocuments(knowledgeBase);
  }, [loadDocuments]);

  const openRetrieve = useCallback((rows: KnowledgeBaseRow[]) => {
    const knowledgeBase = rows?.[0];
    if (!knowledgeBase?.id) return;
    setSelectedKnowledgeBase(knowledgeBase);
    setRetrievalResult(null);
    retrieveForm.setFieldsValue({
      query: '',
      mode: knowledgeBase.retrievalMode || 'HYBRID',
      topK: 10,
      scoreThreshold: 0,
    });
    setRetrieveOpen(true);
  }, [retrieveForm]);

  const handleUpload = useCallback(async () => {
    const target = uploadFiles[0]?.originFileObj as File | undefined;
    if (!selectedKnowledgeBase || !target) {
      message.warning(t('ai.knowledge-bases.warning.selectFile', '请选择要上传的文档'));
      return;
    }
    const formData = new FormData();
    formData.append('file', target);
    setUploading(true);
    try {
      const result = await request<KnowledgeDocument>(
        `${baseConfig.baseUrl}/${selectedKnowledgeBase.id}/documents/upload`,
        {method: 'POST', body: formData},
      );
      if (result.status === 'FAILED') {
        message.error(result.errorMessage || t('ai.knowledge-bases.error.index', '文档索引失败'));
      } else {
        message.success(t('ai.knowledge-bases.success.upload', '文档上传并索引成功'));
      }
      setUploadOpen(false);
      setUploadFiles([]);
      await loadDocuments(selectedKnowledgeBase);
      refreshKnowledgeBases();
    } finally {
      setUploading(false);
    }
  }, [baseConfig.baseUrl, loadDocuments, refreshKnowledgeBases, selectedKnowledgeBase, t, uploadFiles]);

  const handleAddText = useCallback(async () => {
    if (!selectedKnowledgeBase) return;
    const values = await textForm.validateFields();
    setTextSaving(true);
    try {
      const result = await post<KnowledgeDocument>(
        `${baseConfig.baseUrl}/${selectedKnowledgeBase.id}/documents/text`,
        values,
      );
      if (result.status === 'FAILED') {
        message.error(result.errorMessage || t('ai.knowledge-bases.error.index', '文档索引失败'));
      } else {
        message.success(t('ai.knowledge-bases.success.text', '文本文档索引成功'));
      }
      setTextOpen(false);
      textForm.resetFields();
      await loadDocuments(selectedKnowledgeBase);
      refreshKnowledgeBases();
    } finally {
      setTextSaving(false);
    }
  }, [baseConfig.baseUrl, loadDocuments, refreshKnowledgeBases, selectedKnowledgeBase, t, textForm]);

  const handleReindex = useCallback(async (document: KnowledgeDocument) => {
    if (!selectedKnowledgeBase) return;
    const hide = message.loading(t('ai.knowledge-bases.progress.reindex', '正在重新索引...'), 0);
    try {
      const result = await post<KnowledgeDocument>(
        `${baseConfig.baseUrl}/${selectedKnowledgeBase.id}/documents/${document.id}/reindex`,
        {},
      );
      hide();
      if (result.status === 'FAILED') {
        message.error(result.errorMessage || t('ai.knowledge-bases.error.index', '文档索引失败'));
      } else {
        message.success(t('ai.knowledge-bases.success.reindex', '重新索引完成'));
      }
      await loadDocuments(selectedKnowledgeBase);
      refreshKnowledgeBases();
    } catch (error) {
      hide();
      throw error;
    }
  }, [baseConfig.baseUrl, loadDocuments, refreshKnowledgeBases, selectedKnowledgeBase, t]);

  const handleDeleteDocument = useCallback(async (document: KnowledgeDocument) => {
    if (!selectedKnowledgeBase) return;
    await del(`${baseConfig.baseUrl}/${selectedKnowledgeBase.id}/documents`, document.id);
    message.success(t('table.deleteSuccess', '删除成功'));
    await loadDocuments(selectedKnowledgeBase);
    refreshKnowledgeBases();
  }, [baseConfig.baseUrl, loadDocuments, refreshKnowledgeBases, selectedKnowledgeBase, t]);

  const handleRetrieve = useCallback(async () => {
    if (!selectedKnowledgeBase) return;
    const values = await retrieveForm.validateFields();
    setRetrieving(true);
    try {
      const result = await post<RetrievalResult>(
        `${baseConfig.baseUrl}/${selectedKnowledgeBase.id}/retrieve`,
        values,
      );
      setRetrievalResult(result);
      if (!result.hits?.length) {
        message.info(t('ai.knowledge-bases.retrieve.empty', '没有找到达到相关度要求的内容'));
      }
    } finally {
      setRetrieving(false);
    }
  }, [baseConfig.baseUrl, retrieveForm, selectedKnowledgeBase, t]);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema?.properties ?? {};
    if (properties.embeddingModelId) {
      properties.embeddingModelId.oneOf = embeddingModels.map((model) => ({
        const: model.id,
        title: `${model.displayName || model.modelId || model.id}${model.providerName ? ` · ${model.providerName}` : ''}`,
      }));
    }
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
  }, [embeddingModels, t]);

  const columnOverrides = useMemo(() => ({
    retrievalMode: {
      width: 120,
      render: (value: RetrievalMode) => t(`ai.knowledge-bases.mode.${value}`, value || '-'),
    },
    enabled: {
      width: 100,
      render: (value: boolean) => (
        <Tag color={value ? 'green' : 'default'}>
          {value ? t('ai.common.enabled', '已启用') : t('ai.common.disabled', '已禁用')}
        </Tag>
      ),
    },
    documentCount: {width: 100},
    chunkCount: {width: 100},
  }), [t]);

  const documentColumns: ColumnsType<KnowledgeDocument> = [
    {title: t('ai.knowledge-documents.title.name', '文档名称'), dataIndex: 'name', ellipsis: true},
    {title: t('ai.knowledge-documents.title.sourceType', '来源'), dataIndex: 'sourceType', width: 90},
    {title: t('ai.knowledge-documents.title.fileSize', '大小'), dataIndex: 'fileSize', width: 100, render: formatBytes},
    {
      title: t('ai.knowledge-documents.title.status', '状态'),
      dataIndex: 'status',
      width: 100,
      render: (value, record) => (
        <Tag color={value === 'READY' ? 'green' : value === 'FAILED' ? 'red' : 'blue'} title={record.errorMessage}>
          {t(`ai.knowledge-documents.status.${value}`, value || '-')}
        </Tag>
      ),
    },
    {title: t('ai.knowledge-documents.title.chunkCount', '分块数'), dataIndex: 'chunkCount', width: 90},
    {
      title: t('table.action', '操作'),
      key: 'actions',
      width: 130,
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" icon={<ReloadOutlined/>} onClick={() => void handleReindex(record)}>
            {t('ai.knowledge-bases.button.reindex', '重建')}
          </Button>
          <Popconfirm title={t('table.deleteConfirm', '确定删除吗？')} onConfirm={() => void handleDeleteDocument(record)}>
            <Button type="link" danger size="small" icon={<DeleteOutlined/>}/>
          </Popconfirm>
        </Space>
      ),
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

  return (
    <>
      <SimpleTable
        key={tableKey}
        {...baseConfig}
        customButtonEvents={customButtonEvents}
        formSchemaTransform={formSchemaTransform}
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
        width={1100}
        open={documentsOpen}
        title={`${selectedKnowledgeBase?.name || ''} · ${t('ai.knowledge-bases.documents.title', '文档管理')}`}
        footer={null}
        onCancel={() => setDocumentsOpen(false)}
      >
        <Space style={{marginBottom: 16}}>
          <Button type="primary" onClick={() => setUploadOpen(true)}>
            {t('ai.knowledge-bases.documents.upload', '上传文档')}
          </Button>
          <Button onClick={() => setTextOpen(true)}>
            {t('ai.knowledge-bases.documents.addText', '新增文本')}
          </Button>
          <Text type="secondary">
            {t('ai.knowledge-bases.documents.supported', '支持 PDF、Office、OpenDocument、TXT、Markdown、CSV、JSON、XML、HTML、RTF 和 EPUB')}
          </Text>
        </Space>
        <Table
          rowKey="id"
          size="small"
          loading={documentsLoading}
          columns={documentColumns}
          dataSource={documents.content}
          pagination={{pageSize: 10, showSizeChanger: true}}
        />
      </Modal>

      <Modal
        open={uploadOpen}
        title={t('ai.knowledge-bases.documents.upload', '上传文档')}
        confirmLoading={uploading}
        onOk={() => void handleUpload()}
        onCancel={() => { setUploadOpen(false); setUploadFiles([]); }}
      >
        <Upload.Dragger
          beforeUpload={() => false}
          maxCount={1}
          fileList={uploadFiles}
          onChange={({fileList}) => setUploadFiles(fileList.slice(-1))}
        >
          <p className="ant-upload-drag-icon"><InboxOutlined/></p>
          <p>{t('ai.knowledge-bases.documents.drop', '点击或拖拽文档到此处')}</p>
          <p className="ant-upload-hint">{t('ai.knowledge-bases.documents.maxSize', '单个文档最大 20 MB')}</p>
        </Upload.Dragger>
      </Modal>

      <Modal
        open={textOpen}
        title={t('ai.knowledge-bases.documents.addText', '新增文本')}
        confirmLoading={textSaving}
        onOk={() => void handleAddText()}
        onCancel={() => { setTextOpen(false); textForm.resetFields(); }}
      >
        <Form form={textForm} layout="vertical">
          <Form.Item name="name" label={t('ai.knowledge-documents.title.name', '文档名称')} rules={[{required: true}]}>
            <Input maxLength={512}/>
          </Form.Item>
          <Form.Item name="content" label={t('ai.knowledge-bases.documents.content', '文本内容')} rules={[{required: true}]}>
            <TextArea rows={12} showCount/>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        width={1100}
        open={retrieveOpen}
        title={`${selectedKnowledgeBase?.name || ''} · ${t('ai.knowledge-bases.retrieve.title', '检索测试')}`}
        footer={null}
        onCancel={() => setRetrieveOpen(false)}
      >
        <Alert
          type="info"
          showIcon
          style={{marginBottom: 16}}
          message={t('ai.knowledge-bases.retrieve.tip', '可临时覆盖检索模式、Top K 和最低相关度，不会修改知识库配置。')}
        />
        <Form form={retrieveForm} layout="inline" onFinish={() => void handleRetrieve()} style={{marginBottom: 16}}>
          <Form.Item name="query" rules={[{required: true}]} style={{flex: 1}}>
            <Input.Search
              placeholder={t('ai.knowledge-bases.retrieve.placeholder', '输入要检索的问题')}
              enterButton={t('ai.knowledge-bases.button.retrieve', '检索')}
              loading={retrieving}
              onSearch={() => void handleRetrieve()}
            />
          </Form.Item>
          <Form.Item name="mode">
            <Select style={{width: 130}} options={[
              {value: 'HYBRID', label: t('ai.knowledge-bases.mode.HYBRID', '混合检索')},
              {value: 'VECTOR', label: t('ai.knowledge-bases.mode.VECTOR', '向量检索')},
              {value: 'KEYWORD', label: t('ai.knowledge-bases.mode.KEYWORD', '关键词检索')},
            ]}/>
          </Form.Item>
          <Form.Item name="topK"><InputNumber min={1} max={100} addonBefore="Top K"/></Form.Item>
          <Form.Item name="scoreThreshold"><InputNumber min={0} max={1} step={0.05} addonBefore={t('ai.knowledge-bases.retrieve.threshold', '阈值')}/></Form.Item>
        </Form>
        <Table
          rowKey="chunkId"
          size="small"
          loading={retrieving}
          columns={hitColumns}
          dataSource={retrievalResult?.hits || []}
          pagination={false}
          scroll={{y: 520}}
        />
      </Modal>
    </>
  );
};

export default KnowledgeBaseView;
