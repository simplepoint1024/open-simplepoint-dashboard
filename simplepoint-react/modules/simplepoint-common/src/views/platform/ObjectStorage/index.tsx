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
const uploadConfig = api['object-storage'];
const tenantsConfig = api['platform.tenants'];

type StoragePlatformType = 'MINIO' | 'S3' | 'ALIYUN_OSS' | 'TENCENT_COS' | 'QINIU_KODO' | 'CEPH';
type StorageSection = 'providers' | 'objects' | 'quotas';

type ProviderDefinition = {
  code: string;
  name: string;
  type: StoragePlatformType;
  endpoint?: string | null;
  bucket?: string | null;
  defaultProvider?: boolean | null;
};

type ProviderConfig = {
  id: string;
  code: string;
  name: string;
  type: StoragePlatformType;
  endpoint?: string | null;
  region?: string | null;
  accessKey: string;
  bucket: string;
  basePath?: string | null;
  pathStyleAccess?: boolean | null;
  publicBaseUrl?: string | null;
  enabled: boolean;
  defaultProvider: boolean;
  description?: string | null;
  secretConfigured?: boolean | null;
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
  directory?: string;
  file: UploadFile[];
};

type ProviderFormValues = Omit<ProviderConfig, 'id' | 'secretConfigured'> & {
  id?: string;
  secretKey?: string;
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
  const storageSection = useMemo<StorageSection>(() => {
    const path = typeof window === 'undefined' ? '' : window.location.hash;
    if (path.includes('/system/object-storage')) return 'providers';
    if (path.includes('/platform/storage-quotas')) return 'quotas';
    return 'objects';
  }, []);
  const [uploadForm] = Form.useForm<UploadFormValues>();
  const [quotaForm] = Form.useForm<QuotaFormValues>();
  const [providerForm] = Form.useForm<ProviderFormValues>();

  const [providers, setProviders] = useState<ProviderDefinition[]>([]);
  const [providerConfigsPage, setProviderConfigsPage] = useState<Page<ProviderConfig>>(emptyPage());
  const [tenantOptions, setTenantOptions] = useState<TenantSummary[]>([]);
  const [objectFilters, setObjectFilters] = useState<ObjectFilterState>({ originalFileName: '' });
  const [quotaTenantFilter, setQuotaTenantFilter] = useState('');
  const [objectsPage, setObjectsPage] = useState<Page<StorageObject>>(emptyPage());
  const [quotasPage, setQuotasPage] = useState<Page<StorageQuota>>(emptyPage());
  const [objectsLoading, setObjectsLoading] = useState(false);
  const [quotasLoading, setQuotasLoading] = useState(false);
  const [providerConfigsLoading, setProviderConfigsLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [quotaSaving, setQuotaSaving] = useState(false);
  const [providerSaving, setProviderSaving] = useState(false);
  const [providerTestingId, setProviderTestingId] = useState<string | null>(null);
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [quotaModalOpen, setQuotaModalOpen] = useState(false);
  const [providerModalOpen, setProviderModalOpen] = useState(false);
  const [quotaMode, setQuotaMode] = useState<'create' | 'edit'>('create');
  const [providerMode, setProviderMode] = useState<'create' | 'edit'>('create');
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [selectedObject, setSelectedObject] = useState<StorageObject | null>(null);

  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [ensure, locale]);

  const loadProviders = useCallback(async () => {
    const data = await get<ProviderDefinition[]>(`${baseConfig.baseUrl}/providers`);
    setProviders(data);
  }, []);

  const loadProviderConfigs = useCallback(async (pageNumber: number, pageSize: number) => {
    setProviderConfigsLoading(true);
    try {
      const data = await get<Page<ProviderConfig>>(`${baseConfig.baseUrl}/provider-configs`, {
        page: Math.max(pageNumber - 1, 0),
        size: pageSize,
      });
      setProviderConfigsPage(data);
    } finally {
      setProviderConfigsLoading(false);
    }
  }, []);

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
    if (storageSection === 'providers') {
      void Promise.all([loadProviders(), loadProviderConfigs(1, 10)]);
      return;
    }
    if (storageSection === 'quotas') {
      void Promise.all([loadTenants(), loadQuotas(1, 10)]);
      return;
    }
    void Promise.all([loadProviders(), loadObjects(1, 10)]);
  }, [loadObjects, loadProviderConfigs, loadProviders, loadQuotas, loadTenants, storageSection]);

  const refreshObjects = useCallback(async () => {
    await loadObjects(objectsPage.page.number + 1, objectsPage.page.size || 10);
  }, [loadObjects, objectsPage.page.number, objectsPage.page.size]);

  const refreshQuotas = useCallback(async () => {
    await loadQuotas(quotasPage.page.number + 1, quotasPage.page.size || 10);
  }, [loadQuotas, quotasPage.page.number, quotasPage.page.size]);

  const openUploadModal = useCallback(() => {
    uploadForm.setFieldsValue({
      directory: '',
      file: [],
    });
    setUploadModalOpen(true);
  }, [uploadForm]);

  const openProviderCreateModal = useCallback(() => {
    setProviderMode('create');
    providerForm.setFieldsValue({
      code: '',
      name: '',
      type: 'MINIO',
      endpoint: '',
      region: 'us-east-1',
      accessKey: '',
      secretKey: '',
      bucket: '',
      basePath: '',
      pathStyleAccess: true,
      publicBaseUrl: '',
      enabled: true,
      defaultProvider: providerConfigsPage.page.totalElements === 0,
      description: '',
    });
    setProviderModalOpen(true);
  }, [providerConfigsPage.page.totalElements, providerForm]);

  const openProviderEditModal = useCallback((record: ProviderConfig) => {
    setProviderMode('edit');
    providerForm.setFieldsValue({
      ...record,
      secretKey: '',
      endpoint: record.endpoint ?? '',
      region: record.region ?? 'us-east-1',
      basePath: record.basePath ?? '',
      publicBaseUrl: record.publicBaseUrl ?? '',
      description: record.description ?? '',
    });
    setProviderModalOpen(true);
  }, [providerForm]);

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
    if (values.directory?.trim()) {
      formData.append('directory', values.directory.trim());
    }
    setUploading(true);
    try {
      await request<StorageObject>(`${uploadConfig.baseUrl}/upload`, {
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
  }, [refreshObjects, refreshQuotas, t, uploadForm]);

  const handleSaveProvider = useCallback(async () => {
    const values = await providerForm.validateFields();
    const payload = {
      ...values,
      endpoint: values.endpoint?.trim() || null,
      region: values.region?.trim() || 'us-east-1',
      accessKey: values.accessKey.trim(),
      secretKey: values.secretKey?.trim() || null,
      bucket: values.bucket.trim(),
      basePath: values.basePath?.trim() || null,
      publicBaseUrl: values.publicBaseUrl?.trim() || null,
      description: values.description?.trim() || null,
    };
    setProviderSaving(true);
    try {
      if (providerMode === 'create') {
        await post<ProviderConfig>(`${baseConfig.baseUrl}/provider-configs`, payload);
        message.success(t('table.addSuccess', '新增成功'));
      } else {
        await put<ProviderConfig>(`${baseConfig.baseUrl}/provider-configs`, payload);
        message.success(t('table.editSuccess', '修改成功'));
      }
      setProviderModalOpen(false);
      providerForm.resetFields();
      await Promise.all([
        loadProviderConfigs(1, providerConfigsPage.page.size || 10),
        loadProviders(),
      ]);
    } finally {
      setProviderSaving(false);
    }
  }, [baseConfig.baseUrl, loadProviderConfigs, loadProviders, providerConfigsPage.page.size, providerForm, providerMode, t]);

  const handleDeleteProvider = useCallback(async (record: ProviderConfig) => {
    await request<string[]>(`${baseConfig.baseUrl}/provider-configs?ids=${encodeURIComponent(record.id)}`, {
      method: 'DELETE',
    });
    message.success(t('table.deleteSuccess', '删除成功'));
    await Promise.all([
      loadProviderConfigs(1, providerConfigsPage.page.size || 10),
      loadProviders(),
    ]);
  }, [baseConfig.baseUrl, loadProviderConfigs, loadProviders, providerConfigsPage.page.size, t]);

  const handleTestProvider = useCallback(async (record: ProviderConfig) => {
    setProviderTestingId(record.id);
    try {
      await post<{ success: boolean; message: string }>(
        `${baseConfig.baseUrl}/provider-configs/${record.id}/test`,
        {}
      );
      message.success(t('storage.provider.testSuccess', '连接成功，Bucket 可访问'));
    } finally {
      setProviderTestingId(null);
    }
  }, [baseConfig.baseUrl, t]);

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
      render: (value?: boolean | null) => value === false
        ? <Tag color="default">{t('storage.disabled', '停用')}</Tag>
        : <Tag color="success">{t('storage.enabled', '启用')}</Tag>,
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

  const providerColumns = useMemo<ColumnsType<ProviderConfig>>(() => [
    {
      title: t('storage.provider.name', '配置名称'),
      dataIndex: 'name',
      key: 'name',
      width: 190,
      render: (value: string, record) => (
        <Space direction="vertical" size={0}>
          <Space>
            <Text strong>{value}</Text>
            {record.defaultProvider ? <Tag color="blue">{t('storage.provider.default', '系统默认')}</Tag> : null}
          </Space>
          <Text type="secondary">{record.code}</Text>
        </Space>
      ),
    },
    {
      title: t('storage.provider.type', '存储平台'),
      dataIndex: 'type',
      key: 'type',
      width: 150,
      render: (value: StoragePlatformType) => <Tag>{t(`storage.provider.type.${value}`, value)}</Tag>,
    },
    {
      title: t('storage.provider.endpoint', '服务端点'),
      dataIndex: 'endpoint',
      key: 'endpoint',
      render: (value?: string | null) => value || t('storage.provider.awsDefaultEndpoint', 'AWS 默认端点'),
    },
    {
      title: t('storage.bucket', 'Bucket'),
      dataIndex: 'bucket',
      key: 'bucket',
      width: 160,
    },
    {
      title: t('storage.provider.region', '区域'),
      dataIndex: 'region',
      key: 'region',
      width: 130,
      render: (value?: string | null) => value || '--',
    },
    {
      title: t('storage.provider.credential', '凭证'),
      dataIndex: 'secretConfigured',
      key: 'secretConfigured',
      width: 100,
      render: (value?: boolean | null) => value
        ? <Tag color="success">{t('storage.provider.configured', '已配置')}</Tag>
        : <Tag color="error">{t('storage.provider.notConfigured', '未配置')}</Tag>,
    },
    {
      title: t('storage.enabled', '启用'),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 90,
      render: (value: boolean) => value
        ? <Tag color="success">{t('storage.enabled', '启用')}</Tag>
        : <Tag>{t('storage.disabled', '停用')}</Tag>,
    },
    {
      title: t('table.action', '操作'),
      key: 'action',
      width: 220,
      render: (_value, record) => (
        <Space>
          <Button
            type="link"
            loading={providerTestingId === record.id}
            onClick={() => void handleTestProvider(record)}
          >
            {t('storage.provider.test', '测试连接')}
          </Button>
          <Button type="link" onClick={() => openProviderEditModal(record)}>
            {t('table.button.edit', '编辑')}
          </Button>
          <Popconfirm
            title={t('table.confirmDeleteTitle', '确认删除')}
            description={t('storage.provider.deleteConfirm', '已被文件引用的配置不能删除，确认继续吗？')}
            onConfirm={() => void handleDeleteProvider(record)}
          >
            <Button danger type="link">{t('table.button.delete', '删除')}</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ], [handleDeleteProvider, handleTestProvider, openProviderEditModal, providerTestingId, t]);

  return (
    <div>
      <Tabs
        activeKey={storageSection}
        items={[
          {
            key: 'providers',
            label: t('storage.page.providers', 'OSS 配置'),
            children: (
              <Space direction="vertical" style={{ width: '100%' }} size={16}>
                <Alert
                  type="info"
                  showIcon
                  message={t(
                    'storage.provider.tip',
                    '配置由系统管理员统一维护。系统默认配置会用于统一文件上传入口，Secret Key 将加密保存。'
                  )}
                />
                <Space wrap>
                  <Button
                    type="primary"
                    icon={<PlusOutlined />}
                    onClick={openProviderCreateModal}
                  >
                    {t('storage.provider.add', '新增 OSS 配置')}
                  </Button>
                  <Button onClick={() => void loadProviderConfigs(
                    providerConfigsPage.page.number + 1,
                    providerConfigsPage.page.size || 10
                  )}>
                    {t('action.refresh', '刷新')}
                  </Button>
                </Space>
                <Table<ProviderConfig>
                  rowKey="id"
                  loading={providerConfigsLoading}
                  columns={providerColumns}
                  dataSource={providerConfigsPage.content}
                  pagination={{
                    current: providerConfigsPage.page.number + 1,
                    pageSize: providerConfigsPage.page.size || 10,
                    total: providerConfigsPage.page.totalElements || 0,
                    showSizeChanger: true,
                  }}
                  onChange={(pagination: TablePaginationConfig) => {
                    void loadProviderConfigs(pagination.current || 1, pagination.pageSize || 10);
                  }}
                />
              </Space>
            ),
          },
          {
            key: 'objects',
            label: t('storage.page.objects', '对象列表'),
            children: (
              <Space direction="vertical" style={{ width: '100%' }} size={16}>
                {providers.length === 0 ? (
                  <Alert
                    type="warning"
                    showIcon
                    message={t('storage.page.noProviders', '当前没有可用的 OSS 配置，请先在“OSS 配置”页签新增并启用连接。')}
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
        ].filter((item) => item.key === storageSection)}
      />

      <Modal
        title={providerMode === 'create'
          ? t('storage.provider.add', '新增 OSS 配置')
          : t('storage.provider.edit', '编辑 OSS 配置')}
        open={providerModalOpen}
        width={760}
        onCancel={() => {
          setProviderModalOpen(false);
          providerForm.resetFields();
        }}
        onOk={() => void handleSaveProvider()}
        confirmLoading={providerSaving}
        destroyOnHidden
      >
        <Form form={providerForm} layout="vertical">
          <Form.Item name="id" hidden><Input /></Form.Item>
          <Space align="start" style={{ width: '100%' }} size={16}>
            <Form.Item
              name="name"
              label={t('storage.provider.name', '配置名称')}
              rules={[{ required: true, message: t('form.required', '这是必填项') }]}
              style={{ width: 230 }}
            >
              <Input placeholder={t('storage.provider.namePlaceholder', '生产环境对象存储')} />
            </Form.Item>
            <Form.Item
              name="code"
              label={t('storage.provider.code', '配置编码')}
              rules={[
                { required: true, message: t('form.required', '这是必填项') },
                { pattern: /^[a-z0-9][a-z0-9._-]{1,63}$/, message: t('storage.provider.codeRule', '请输入小写字母、数字、点、下划线或短横线') },
              ]}
              style={{ width: 230 }}
            >
              <Input disabled={providerMode === 'edit'} placeholder="minio-prod" />
            </Form.Item>
            <Form.Item
              name="type"
              label={t('storage.provider.type', '存储平台')}
              rules={[{ required: true }]}
              style={{ width: 230 }}
            >
              <Select
                options={(['MINIO', 'S3', 'ALIYUN_OSS', 'TENCENT_COS', 'QINIU_KODO', 'CEPH'] as StoragePlatformType[])
                  .map((value) => ({ label: t(`storage.provider.type.${value}`, value), value }))}
                onChange={(value: StoragePlatformType) => {
                  if (value === 'MINIO' || value === 'CEPH') {
                    providerForm.setFieldValue('pathStyleAccess', true);
                  }
                }}
              />
            </Form.Item>
          </Space>
          <Form.Item
            name="endpoint"
            label={t('storage.provider.endpoint', '服务端点')}
            tooltip={t('storage.provider.endpointTip', 'AWS S3 可留空使用默认端点，其他平台请填写完整 HTTP(S) 地址')}
          >
            <Input placeholder="https://s3.example.com" />
          </Form.Item>
          <Space align="start" style={{ width: '100%' }} size={16}>
            <Form.Item
              name="region"
              label={t('storage.provider.region', '区域')}
              rules={[{ required: true }]}
              style={{ width: 230 }}
            >
              <Input placeholder="us-east-1" />
            </Form.Item>
            <Form.Item
              name="bucket"
              label={t('storage.bucket', 'Bucket')}
              rules={[{ required: true, message: t('form.required', '这是必填项') }]}
              style={{ width: 230 }}
            >
              <Input />
            </Form.Item>
            <Form.Item name="pathStyleAccess" label={t('storage.provider.pathStyle', 'Path Style')} valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>
          <Space align="start" style={{ width: '100%' }} size={16}>
            <Form.Item
              name="accessKey"
              label={t('storage.provider.accessKey', 'Access Key')}
              rules={[{ required: true, message: t('form.required', '这是必填项') }]}
              style={{ width: 350 }}
            >
              <Input autoComplete="off" />
            </Form.Item>
            <Form.Item
              name="secretKey"
              label={t('storage.provider.secretKey', 'Secret Key')}
              tooltip={providerMode === 'edit' ? t('storage.provider.secretKeep', '留空表示保持现有凭证不变') : undefined}
              rules={providerMode === 'create' ? [{ required: true, message: t('form.required', '这是必填项') }] : []}
              style={{ width: 350 }}
            >
              <Input.Password autoComplete="new-password" />
            </Form.Item>
          </Space>
          <Form.Item name="basePath" label={t('storage.provider.basePath', '存储根目录')}>
            <Input placeholder="simplepoint" />
          </Form.Item>
          <Form.Item name="publicBaseUrl" label={t('storage.provider.publicBaseUrl', '公开访问地址')}>
            <Input placeholder="https://cdn.example.com" />
          </Form.Item>
          <Space size={32}>
            <Form.Item name="enabled" label={t('storage.enabled', '启用')} valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item
              name="defaultProvider"
              label={t('storage.provider.default', '系统默认')}
              valuePropName="checked"
              tooltip={t('storage.provider.defaultTip', '统一上传入口未指定连接时使用该配置')}
            >
              <Switch />
            </Form.Item>
          </Space>
          <Form.Item name="description" label={t('storage.description', '说明')}>
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>

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
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={t('storage.upload.defaultProvider', '文件将通过统一上传入口写入系统默认 OSS，并自动加入当前租户目录。')}
          />
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
            <Descriptions.Item label={t('storage.eTag', 'ETag')}>{selectedObject.eTag || '--'}</Descriptions.Item>
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
