import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
  Upload,
  message,
} from 'antd';
import type { ColumnsType, TablePaginationConfig } from 'antd/es/table';
import type { UploadFile } from 'antd/es/upload/interface';
import { DeleteOutlined, DownloadOutlined, EyeOutlined, InboxOutlined, PlusOutlined } from '@ant-design/icons';
import { get, post, put } from '@simplepoint/shared/api/methods';
import { request } from '@simplepoint/shared/api/client';
import type { Page } from '@simplepoint/shared/types/request';
import { useI18n } from '@simplepoint/shared/hooks/useI18n';
import api from '@/api';

const { Paragraph, Text } = Typography;
const baseConfig = api['platform.object-storage'];
const tenantsConfig = api['platform.tenants'];

type ProviderDefinition = {
  code: string;
  name: string;
  type: 'MINIO' | 'S3' | 'ALIYUN_OSS' | 'TENCENT_COS' | 'CEPH';
  endpoint?: string | null;
  bucket?: string | null;
  defaultProvider?: boolean | null;
};

type StorageObject = {
  id: string;
  providerCode: string;
  providerType: ProviderDefinition['type'];
  bucket: string;
  objectKey: string;
  originalFileName: string;
  contentType?: string | null;
  contentLength?: number | null;
  eTag?: string | null;
  accessUrl?: string | null;
  sourceServiceName?: string | null;
  createdAt?: string | null;
};

type StorageQuota = {
  id: string;
  tenantId: string;
  tenantName?: string | null;
  quotaBytes?: number | null;
  usedBytes?: number | null;
  remainingBytes?: number | null;
  enabled?: boolean | null;
  description?: string | null;
};

type TenantSummary = {
  id: string;
  name: string;
};

type ObjectFilterState = {
  providerCode?: string;
  originalFileName: string;
};

type UploadFormValues = {
  providerCode?: string;
  directory?: string;
  file: UploadFile[];
};

type QuotaFormValues = {
  id?: string;
  tenantId: string;
  quotaMb?: number | null;
  enabled: boolean;
  description?: string;
};

const emptyPage = <T,>(): Page<T> => ({
  content: [],
  page: {
    size: 10,
    totalElements: 0,
    totalPages: 0,
    number: 0,
  },
});

const normalizeUploadEvent = (event: { fileList?: UploadFile[] } | UploadFile[]) => {
  if (Array.isArray(event)) {
    return event;
  }
  return event?.fileList ?? [];
};

const bytesToMb = (bytes?: number | null) => (bytes == null ? null : Number((bytes / 1024 / 1024).toFixed(2)));

const formatBytes = (bytes?: number | null) => {
  if (bytes == null) {
    return '--';
  }
  if (bytes === 0) {
    return '0 B';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${value.toFixed(value >= 10 || unitIndex === 0 ? 0 : 2)} ${units[unitIndex]}`;
};

const formatDateTime = (value?: string | null) => {
  if (!value) {
    return '--';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
};

const App = () => {
  const { ensure, locale, t } = useI18n();
  const [uploadForm] = Form.useForm<UploadFormValues>();
  const [quotaForm] = Form.useForm<QuotaFormValues>();

  const [providers, setProviders] = useState<ProviderDefinition[]>([]);
  const [tenantOptions, setTenantOptions] = useState<TenantSummary[]>([]);
  const [objectFilters, setObjectFilters] = useState<ObjectFilterState>({ originalFileName: '' });
  const [quotaTenantFilter, setQuotaTenantFilter] = useState('');
  const [objectsPage, setObjectsPage] = useState<Page<StorageObject>>(emptyPage());
  const [quotasPage, setQuotasPage] = useState<Page<StorageQuota>>(emptyPage());
  const [objectsLoading, setObjectsLoading] = useState(false);
  const [quotasLoading, setQuotasLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [quotaSaving, setQuotaSaving] = useState(false);
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [quotaModalOpen, setQuotaModalOpen] = useState(false);
  const [quotaMode, setQuotaMode] = useState<'create' | 'edit'>('create');
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [selectedObject, setSelectedObject] = useState<StorageObject | null>(null);

  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [ensure, locale]);

  const loadProviders = useCallback(async () => {
    const data = await get<ProviderDefinition[]>(`${baseConfig.baseUrl}/providers`);
    setProviders(data);
    if (data.length > 0) {
      uploadForm.setFieldValue('providerCode', data.find((item) => item.defaultProvider)?.code ?? data[0].code);
    }
  }, [uploadForm]);

  const loadTenants = useCallback(async () => {
    const data = await get<Page<TenantSummary>>(tenantsConfig.baseUrl, { page: 0, size: 200 });
    setTenantOptions(data.content ?? []);
  }, []);

  const buildObjectParams = useCallback(
    (pageNumber: number, pageSize: number) => {
      const params: Record<string, string | number> = {
        page: Math.max(pageNumber - 1, 0),
        size: pageSize,
      };
      if (objectFilters.providerCode) {
        params.providerCode = objectFilters.providerCode;
      }
      if (objectFilters.originalFileName.trim()) {
        params.originalFileName = objectFilters.originalFileName.trim();
      }
      return params;
    },
    [objectFilters]
  );

  const buildQuotaParams = useCallback((pageNumber: number, pageSize: number) => {
    const params: Record<string, string | number> = {
      page: Math.max(pageNumber - 1, 0),
      size: pageSize,
    };
    if (quotaTenantFilter.trim()) {
      params.tenantId = quotaTenantFilter.trim();
    }
    return params;
  }, [quotaTenantFilter]);

  const loadObjects = useCallback(async (pageNumber: number, pageSize: number) => {
    setObjectsLoading(true);
    try {
      const data = await get<Page<StorageObject>>(`${baseConfig.baseUrl}/objects`, buildObjectParams(pageNumber, pageSize));
      setObjectsPage(data);
    } finally {
      setObjectsLoading(false);
    }
  }, [buildObjectParams]);

  const loadQuotas = useCallback(async (pageNumber: number, pageSize: number) => {
    setQuotasLoading(true);
    try {
      const data = await get<Page<StorageQuota>>(`${baseConfig.baseUrl}/quotas`, buildQuotaParams(pageNumber, pageSize));
      setQuotasPage(data);
    } finally {
      setQuotasLoading(false);
    }
  }, [buildQuotaParams]);

  useEffect(() => {
    void Promise.all([loadProviders(), loadTenants(), loadObjects(1, 10), loadQuotas(1, 10)]);
  }, [loadObjects, loadProviders, loadQuotas, loadTenants]);

  const refreshObjects = useCallback(async () => {
    await loadObjects(objectsPage.page.number + 1, objectsPage.page.size || 10);
  }, [loadObjects, objectsPage.page.number, objectsPage.page.size]);

  const refreshQuotas = useCallback(async () => {
    await loadQuotas(quotasPage.page.number + 1, quotasPage.page.size || 10);
  }, [loadQuotas, quotasPage.page.number, quotasPage.page.size]);

  const openUploadModal = useCallback(() => {
    uploadForm.setFieldsValue({
      providerCode: providers.find((item) => item.defaultProvider)?.code ?? providers[0]?.code,
      directory: '',
      file: [],
    });
    setUploadModalOpen(true);
  }, [providers, uploadForm]);

  const openQuotaCreateModal = useCallback(() => {
    setQuotaMode('create');
    quotaForm.setFieldsValue({
      tenantId: tenantOptions[0]?.id,
      quotaMb: null,
      enabled: true,
      description: '',
    });
    setQuotaModalOpen(true);
  }, [quotaForm, tenantOptions]);

  const openQuotaEditModal = useCallback((record: StorageQuota) => {
    setQuotaMode('edit');
    quotaForm.setFieldsValue({
      id: record.id,
      tenantId: record.tenantId,
      quotaMb: bytesToMb(record.quotaBytes),
      enabled: record.enabled !== false,
      description: record.description ?? '',
    });
    setQuotaModalOpen(true);
  }, [quotaForm]);

  const openDetailModal = useCallback(async (record: StorageObject) => {
    setDetailLoading(true);
    setSelectedObject(null);
    setDetailOpen(true);
    try {
      const detail = await get<StorageObject>(`${baseConfig.baseUrl}/objects/${record.id}`);
      setSelectedObject(detail);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const handleUpload = useCallback(async () => {
    const values = await uploadForm.validateFields();
    const targetFile = values.file?.[0]?.originFileObj as File | undefined;
    if (!targetFile) {
      message.warning(t('form.required', '这是必填项'));
      return;
    }
    const formData = new FormData();
    formData.append('file', targetFile);
    if (values.providerCode) {
      formData.append('providerCode', values.providerCode);
    }
    if (values.directory?.trim()) {
      formData.append('directory', values.directory.trim());
    }
    setUploading(true);
    try {
      await request<StorageObject>(`${baseConfig.baseUrl}/objects/upload`, {
        method: 'POST',
        body: formData,
      });
      message.success(t('table.addSuccess', '新增成功'));
      setUploadModalOpen(false);
      uploadForm.resetFields();
      await refreshObjects();
      await refreshQuotas();
    } finally {
      setUploading(false);
    }
  }, [baseConfig.baseUrl, refreshObjects, refreshQuotas, t, uploadForm]);

  const handleDeleteObject = useCallback(async (record: StorageObject) => {
    await request<string[]>(`${baseConfig.baseUrl}/objects?ids=${encodeURIComponent(record.id)}`, {
      method: 'DELETE',
    });
    message.success(t('table.deleteSuccess', '删除成功'));
    await refreshObjects();
    await refreshQuotas();
  }, [baseConfig.baseUrl, refreshObjects, refreshQuotas, t]);

  const handleSaveQuota = useCallback(async () => {
    const values = await quotaForm.validateFields();
    const payload = {
      id: values.id,
      tenantId: values.tenantId,
      quotaBytes: values.quotaMb == null ? null : Math.round(values.quotaMb * 1024 * 1024),
      enabled: Boolean(values.enabled),
      description: values.description?.trim() || null,
    };
    setQuotaSaving(true);
    try {
      if (quotaMode === 'create') {
        await post<StorageQuota>(`${baseConfig.baseUrl}/quotas`, payload);
        message.success(t('table.addSuccess', '新增成功'));
      } else {
        await put<StorageQuota>(`${baseConfig.baseUrl}/quotas`, payload);
        message.success(t('table.editSuccess', '修改成功'));
      }
      setQuotaModalOpen(false);
      quotaForm.resetFields();
      await refreshQuotas();
    } finally {
      setQuotaSaving(false);
    }
  }, [baseConfig.baseUrl, quotaForm, quotaMode, refreshQuotas, t]);

  const handleDeleteQuota = useCallback(async (record: StorageQuota) => {
    await request<string[]>(`${baseConfig.baseUrl}/quotas?ids=${encodeURIComponent(record.id)}`, {
      method: 'DELETE',
    });
    message.success(t('table.deleteSuccess', '删除成功'));
    await refreshQuotas();
  }, [baseConfig.baseUrl, refreshQuotas, t]);

  const objectColumns = useMemo<ColumnsType<StorageObject>>(() => [
    {
      title: t('storage.providers', '提供方'),
      dataIndex: 'providerCode',
      key: 'providerCode',
      width: 140,
      render: (value: string, record) => (
        <Space direction="vertical" size={0}>
          <Text strong>{value}</Text>
          <Text type="secondary">{record.providerType}</Text>
        </Space>
      ),
    },
    {
      title: t('storage.fileName', '文件名'),
      dataIndex: 'originalFileName',
      key: 'originalFileName',
      width: 220,
      render: (value: string) => <Text>{value}</Text>,
    },
    {
      title: t('storage.bucket', 'Bucket'),
      dataIndex: 'bucket',
      key: 'bucket',
      width: 160,
      render: (value: string) => <Tag>{value}</Tag>,
    },
    {
      title: t('storage.objectKey', '对象键'),
      dataIndex: 'objectKey',
      key: 'objectKey',
      render: (value: string) => (
        <Paragraph style={{ marginBottom: 0 }} ellipsis={{ rows: 2, tooltip: value }}>
          <Text code>{value}</Text>
        </Paragraph>
      ),
    },
    {
      title: t('storage.size', '大小'),
      dataIndex: 'contentLength',
      key: 'contentLength',
      width: 120,
      render: (value?: number | null) => formatBytes(value),
    },
    {
      title: t('storage.sourceService', '来源服务'),
      dataIndex: 'sourceServiceName',
      key: 'sourceServiceName',
      width: 140,
      render: (value?: string | null) => value ?? '--',
    },
    {
      title: t('storage.createdAt', '创建时间'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (value?: string | null) => formatDateTime(value),
    },
    {
      title: t('table.action', '操作'),
      key: 'action',
      width: 180,
      render: (_value, record) => (
        <Space>
          <Button type="link" icon={<EyeOutlined />} onClick={() => void openDetailModal(record)}>
            {t('storage.detail', '详情')}
          </Button>
          <Button
            type="link"
            icon={<DownloadOutlined />}
            onClick={() => window.open(`${baseConfig.baseUrl}/objects/${record.id}/content`, '_blank', 'noopener,noreferrer')}
          >
            {t('storage.download', '下载')}
          </Button>
          <Popconfirm
            title={t('table.confirmDeleteTitle', '确认删除')}
            description={t('table.confirmDeleteContent', '确定要删除选中的 {count} 条数据吗？', { count: 1 })}
            onConfirm={() => void handleDeleteObject(record)}
          >
            <Button danger type="link" icon={<DeleteOutlined />}>
              {t('table.button.delete', '删除')}
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ], [baseConfig.baseUrl, handleDeleteObject, openDetailModal, t]);

  const quotaColumns = useMemo<ColumnsType<StorageQuota>>(() => [
    {
      title: t('storage.quota.tenantId', '租户'),
      dataIndex: 'tenantId',
      key: 'tenantId',
      width: 200,
      render: (value: string, record) => (
        <Space direction="vertical" size={0}>
          <Text strong>{record.tenantName || value}</Text>
          <Text type="secondary">{value}</Text>
        </Space>
      ),
    },
    {
      title: t('storage.quota.total', '总配额'),
      dataIndex: 'quotaBytes',
      key: 'quotaBytes',
      width: 140,
      render: (value?: number | null) => (value == null ? t('storage.unlimited', '不限额') : formatBytes(value)),
    },
    {
      title: t('storage.quota.used', '已使用'),
      dataIndex: 'usedBytes',
      key: 'usedBytes',
      width: 140,
      render: (value?: number | null) => formatBytes(value),
    },
    {
      title: t('storage.quota.remaining', '剩余'),
      dataIndex: 'remainingBytes',
      key: 'remainingBytes',
      width: 140,
      render: (value, record: StorageQuota) => record.quotaBytes == null ? t('storage.unlimited', '不限额') : formatBytes(value),
    },
    {
      title: t('storage.enabled', '启用'),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 100,
      render: (value?: boolean | null) => value === false ? <Tag color="default">OFF</Tag> : <Tag color="success">ON</Tag>,
    },
    {
      title: t('storage.description', '说明'),
      dataIndex: 'description',
      key: 'description',
      render: (value?: string | null) => value ?? '--',
    },
    {
      title: t('table.action', '操作'),
      key: 'action',
      width: 140,
      render: (_value, record) => (
        <Space>
          <Button type="link" onClick={() => openQuotaEditModal(record)}>
            {t('table.button.edit', '编辑')}
          </Button>
          <Popconfirm
            title={t('table.confirmDeleteTitle', '确认删除')}
            description={t('table.confirmDeleteContent', '确定要删除选中的 {count} 条数据吗？', { count: 1 })}
            onConfirm={() => void handleDeleteQuota(record)}
          >
            <Button danger type="link">
              {t('table.button.delete', '删除')}
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ], [handleDeleteQuota, openQuotaEditModal, t]);

  return (
    <div>
      <Tabs
        items={[
          {
            key: 'objects',
            label: t('storage.page.objects', '对象列表'),
            children: (
              <Space direction="vertical" style={{ width: '100%' }} size={16}>
                {providers.length === 0 ? (
                  <Alert
                    type="warning"
                    showIcon
                    message={t('storage.page.noProviders', '当前未配置可用的对象存储提供方，请先在配置文件中启用至少一个提供方。')}
                  />
                ) : null}
                <Space wrap>
                  <Select
                    allowClear
                    style={{ width: 220 }}
                    placeholder={t('storage.providers', '提供方')}
                    value={objectFilters.providerCode}
                    options={providers.map((provider) => ({
                      label: `${provider.name} (${provider.code})`,
                      value: provider.code,
                    }))}
                    onChange={(value) => setObjectFilters((current) => ({ ...current, providerCode: value }))}
                  />
                  <Input
                    allowClear
                    style={{ width: 260 }}
                    placeholder={t('storage.fileName', '文件名')}
                    value={objectFilters.originalFileName}
                    onChange={(event) => setObjectFilters((current) => ({ ...current, originalFileName: event.target.value }))}
                    onPressEnter={() => void loadObjects(1, objectsPage.page.size || 10)}
                  />
                  <Button type="primary" onClick={() => void loadObjects(1, objectsPage.page.size || 10)}>
                    {t('action.search', '查询')}
                  </Button>
                  <Button onClick={() => void refreshObjects()}>{t('action.refresh', '刷新')}</Button>
                  <Button type="primary" icon={<PlusOutlined />} disabled={providers.length === 0} onClick={openUploadModal}>
                    {t('storage.upload', '上传对象')}
                  </Button>
                </Space>
                <Table<StorageObject>
                  rowKey="id"
                  loading={objectsLoading}
                  columns={objectColumns}
                  dataSource={objectsPage.content}
                  pagination={{
                    current: objectsPage.page.number + 1,
                    pageSize: objectsPage.page.size || 10,
                    total: objectsPage.page.totalElements || 0,
                    showSizeChanger: true,
                  }}
                  onChange={(pagination: TablePaginationConfig) => {
                    void loadObjects(pagination.current || 1, pagination.pageSize || 10);
                  }}
                />
              </Space>
            ),
          },
          {
            key: 'quotas',
            label: t('storage.page.quotas', '租户配额'),
            children: (
              <Space direction="vertical" style={{ width: '100%' }} size={16}>
                <Alert
                  type="info"
                  showIcon
                  message={t('storage.quota.tip', '未配置配额或配额为空时表示租户对象存储不限额。')}
                />
                <Space wrap>
                  <Input
                    allowClear
                    style={{ width: 260 }}
                    placeholder={t('storage.quota.tenantId', '租户ID')}
                    value={quotaTenantFilter}
                    onChange={(event) => setQuotaTenantFilter(event.target.value)}
                    onPressEnter={() => void loadQuotas(1, quotasPage.page.size || 10)}
                  />
                  <Button type="primary" onClick={() => void loadQuotas(1, quotasPage.page.size || 10)}>
                    {t('action.search', '查询')}
                  </Button>
                  <Button onClick={() => void refreshQuotas()}>{t('action.refresh', '刷新')}</Button>
                  <Button type="primary" icon={<PlusOutlined />} onClick={openQuotaCreateModal}>
                    {t('storage.quota.add', '新增配额')}
                  </Button>
                </Space>
                <Table<StorageQuota>
                  rowKey="id"
                  loading={quotasLoading}
                  columns={quotaColumns}
                  dataSource={quotasPage.content}
                  pagination={{
                    current: quotasPage.page.number + 1,
                    pageSize: quotasPage.page.size || 10,
                    total: quotasPage.page.totalElements || 0,
                    showSizeChanger: true,
                  }}
                  onChange={(pagination: TablePaginationConfig) => {
                    void loadQuotas(pagination.current || 1, pagination.pageSize || 10);
                  }}
                />
              </Space>
            ),
          },
        ]}
      />

      <Modal
        title={t('storage.upload', '上传对象')}
        open={uploadModalOpen}
        onCancel={() => {
          setUploadModalOpen(false);
          uploadForm.resetFields();
        }}
        onOk={() => void handleUpload()}
        confirmLoading={uploading}
        destroyOnHidden
      >
        <Form form={uploadForm} layout="vertical" initialValues={{ file: [] }}>
          <Form.Item
            name="providerCode"
            label={t('storage.providers', '提供方')}
            rules={[{ required: true, message: t('form.required', '这是必填项') }]}
          >
            <Select
              options={providers.map((provider) => ({
                label: `${provider.name} / ${provider.bucket}`,
                value: provider.code,
              }))}
            />
          </Form.Item>
          <Form.Item name="directory" label={t('storage.directory', '目录前缀')}>
            <Input placeholder={t('storage.directory.placeholder', '例如：avatars/user')} />
          </Form.Item>
          <Form.Item
            name="file"
            label={t('storage.file', '文件')}
            valuePropName="fileList"
            getValueFromEvent={normalizeUploadEvent}
            rules={[{ required: true, message: t('form.required', '这是必填项') }]}
          >
            <Upload.Dragger beforeUpload={() => false} multiple={false} maxCount={1} name="file">
              <p className="ant-upload-drag-icon">
                <InboxOutlined />
              </p>
              <p className="ant-upload-text">{t('storage.upload.drag', '点击或拖拽文件到此区域上传')}</p>
            </Upload.Dragger>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={quotaMode === 'create' ? t('storage.quota.add', '新增配额') : t('storage.quota.edit', '编辑配额')}
        open={quotaModalOpen}
        onCancel={() => {
          setQuotaModalOpen(false);
          quotaForm.resetFields();
        }}
        onOk={() => void handleSaveQuota()}
        confirmLoading={quotaSaving}
        destroyOnHidden
      >
        <Form form={quotaForm} layout="vertical">
          <Form.Item name="id" hidden>
            <Input />
          </Form.Item>
          <Form.Item
            name="tenantId"
            label={t('storage.quota.tenantId', '租户ID')}
            rules={[{ required: true, message: t('form.required', '这是必填项') }]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              options={tenantOptions.map((tenant) => ({
                label: `${tenant.name} (${tenant.id})`,
                value: tenant.id,
              }))}
            />
          </Form.Item>
          <Form.Item name="quotaMb" label={t('storage.quota.limitMb', '配额（MB，留空表示不限额）')}>
            <InputNumber style={{ width: '100%' }} min={0} precision={2} />
          </Form.Item>
          <Form.Item name="enabled" label={t('storage.enabled', '启用')} valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="description" label={t('storage.description', '说明')}>
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('storage.detail', '对象详情')}
        open={detailOpen}
        footer={null}
        onCancel={() => {
          setDetailOpen(false);
          setSelectedObject(null);
        }}
        destroyOnHidden
      >
        {detailLoading ? (
          <Text>{t('loading.resources', '资源加载中，请稍候...')}</Text>
        ) : selectedObject ? (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label={t('storage.fileName', '文件名')}>
              {selectedObject.originalFileName}
            </Descriptions.Item>
            <Descriptions.Item label={t('storage.providers', '提供方')}>
              {selectedObject.providerCode} / {selectedObject.providerType}
            </Descriptions.Item>
            <Descriptions.Item label={t('storage.bucket', 'Bucket')}>
              {selectedObject.bucket}
            </Descriptions.Item>
            <Descriptions.Item label={t('storage.objectKey', '对象键')}>
              <Text code copyable={{ text: selectedObject.objectKey }}>{selectedObject.objectKey}</Text>
            </Descriptions.Item>
            <Descriptions.Item label={t('storage.size', '大小')}>
              {formatBytes(selectedObject.contentLength)}
            </Descriptions.Item>
            <Descriptions.Item label={t('storage.contentType', '内容类型')}>
              {selectedObject.contentType || '--'}
            </Descriptions.Item>
            <Descriptions.Item label="ETag">{selectedObject.eTag || '--'}</Descriptions.Item>
            <Descriptions.Item label={t('storage.sourceService', '来源服务')}>
              {selectedObject.sourceServiceName || '--'}
            </Descriptions.Item>
            <Descriptions.Item label={t('storage.createdAt', '创建时间')}>
              {formatDateTime(selectedObject.createdAt)}
            </Descriptions.Item>
            <Descriptions.Item label={t('storage.accessUrl', '访问地址')}>
              {selectedObject.accessUrl ? (
                <a href={selectedObject.accessUrl} target="_blank" rel="noreferrer">
                  {selectedObject.accessUrl}
                </a>
              ) : (
                '--'
              )}
            </Descriptions.Item>
          </Descriptions>
        ) : (
          <Text type="secondary">{t('table.loadFail', '加载失败')}</Text>
        )}
      </Modal>
    </div>
  );
};

export default App;
