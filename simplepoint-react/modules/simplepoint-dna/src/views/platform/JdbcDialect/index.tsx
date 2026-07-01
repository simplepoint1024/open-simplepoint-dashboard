import api from '@/api';
import { InboxOutlined, PlusOutlined } from '@ant-design/icons';
import { request } from '@simplepoint/shared/api/client';
import { get } from '@simplepoint/shared/api/methods';
import { useI18n } from '@simplepoint/shared/hooks/useI18n';
import { Alert, Button, Form, Input, Modal, Space, Switch, Table, Tabs, Tag, Typography, Upload, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { UploadFile } from 'antd/es/upload/interface';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { formatDateTime, resolveErrorMessage } from '../shared';

const { Dragger } = Upload;
const { Paragraph, Text } = Typography;
const baseConfig = api['platform.dna-dialects'];

type SourceType = 'CLASSPATH' | 'URL' | 'UPLOAD';

type DialectDescriptor = {
  code: string;
  name: string;
  description?: string | null;
  version?: string | null;
  className?: string | null;
  sourceType: SourceType;
  sourceId?: string | null;
  sourceName?: string | null;
  sourceUrl?: string | null;
  localJarPath?: string | null;
  enabled?: boolean | null;
  lastLoadMessage?: string | null;
  loadedAt?: string | null;
  boundDriverCodes?: string[] | null;
  order?: number;
};

type DialectSourceSummary = {
  id: string;
  name: string;
  sourceType: Exclude<SourceType, 'CLASSPATH'>;
  sourceUrl?: string | null;
  localJarPath?: string | null;
  enabled?: boolean | null;
  description?: string | null;
  loadedAt?: string | null;
  lastLoadMessage?: string | null;
  discoveredDialectCodes?: string[] | null;
};

type UrlSourceFormValues = {
  name?: string;
  sourceUrl?: string;
  enabled?: boolean;
  description?: string;
};

type UploadSourceFormValues = {
  name?: string;
  enabled?: boolean;
  description?: string;
  file?: UploadFile[];
};

const normalizeUploadEvent = (event: { fileList?: UploadFile[] } | UploadFile[]) => {
  if (Array.isArray(event)) {
    return event;
  }
  return event?.fileList ?? [];
};

const sourceTypeColorMap: Record<SourceType, string> = {
  CLASSPATH: 'blue',
  URL: 'gold',
  UPLOAD: 'purple',
};

const App = () => {
  const { t, ensure, locale } = useI18n();
  const [loadedDialects, setLoadedDialects] = useState<DialectDescriptor[]>([]);
  const [sources, setSources] = useState<DialectSourceSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [sourceLoading, setSourceLoading] = useState(false);
  const [urlModalOpen, setUrlModalOpen] = useState(false);
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [selectedSourceIds, setSelectedSourceIds] = useState<React.Key[]>([]);
  const [urlForm] = Form.useForm<UrlSourceFormValues>();
  const [uploadForm] = Form.useForm<UploadSourceFormValues>();

  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [ensure, locale]);

  const resolveSourceTypeLabel = useCallback((value: SourceType) => {
    return t(`dna.dialects.page.sourceType.${value}`, value);
  }, [t]);

  const loadDialects = useCallback(async () => {
    setLoading(true);
    try {
      const data = await get<DialectDescriptor[]>(baseConfig.baseUrl);
      setLoadedDialects(data ?? []);
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.dialects.page.error.loadDialects', 'Failed to load dialects.')));
    } finally {
      setLoading(false);
    }
  }, [t]);

  const loadSources = useCallback(async () => {
    setSourceLoading(true);
    try {
      const data = await get<DialectSourceSummary[]>(`${baseConfig.baseUrl}/sources`);
      setSources(data ?? []);
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.dialects.page.error.loadSources', 'Failed to load dialect sources.')));
    } finally {
      setSourceLoading(false);
    }
  }, [t]);

  const reload = useCallback(async () => {
    await Promise.all([loadDialects(), loadSources()]);
  }, [loadDialects, loadSources]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const openUrlModal = useCallback(() => {
    urlForm.setFieldsValue({ enabled: true });
    setUrlModalOpen(true);
  }, [urlForm]);

  const openUploadModal = useCallback(() => {
    uploadForm.setFieldsValue({ enabled: true, file: [] });
    setUploadModalOpen(true);
  }, [uploadForm]);

  const handleCreateUrlSource = useCallback(async () => {
    const values = await urlForm.validateFields();
    setSaving(true);
    try {
      await request(`${baseConfig.baseUrl}/url`, {
        method: 'POST',
        body: JSON.stringify({
          name: values.name?.trim(),
          sourceUrl: values.sourceUrl?.trim(),
          enabled: values.enabled !== false,
          description: values.description?.trim() || null,
        }),
      });
      message.success(t('dna.dialects.page.success.createUrl', 'URL dialect source created successfully.'));
      setUrlModalOpen(false);
      urlForm.resetFields();
      await reload();
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.dialects.page.error.createUrl', 'Failed to create URL dialect source.')));
    } finally {
      setSaving(false);
    }
  }, [baseConfig.baseUrl, reload, t, urlForm]);

  const handleUploadSource = useCallback(async () => {
    const values = await uploadForm.validateFields();
    const file = values.file?.[0]?.originFileObj as File | undefined;
    if (!file) {
      message.warning(t('dna.dialects.page.warning.selectJar', 'Select a dialect jar first.'));
      return;
    }
    const formData = new FormData();
    formData.append('file', file);
    formData.append('name', values.name?.trim() || '');
    formData.append('enabled', String(values.enabled !== false));
    if (values.description?.trim()) {
      formData.append('description', values.description.trim());
    }
    setSaving(true);
    try {
      await request(`${baseConfig.baseUrl}/upload`, {
        method: 'POST',
        body: formData,
      });
      message.success(t('dna.dialects.page.success.upload', 'Dialect jar uploaded successfully.'));
      setUploadModalOpen(false);
      uploadForm.resetFields();
      await reload();
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.dialects.page.error.upload', 'Failed to upload dialect jar.')));
    } finally {
      setSaving(false);
    }
  }, [baseConfig.baseUrl, reload, t, uploadForm]);

  const handleDeleteSources = useCallback(async () => {
    if (selectedSourceIds.length === 0) {
      message.warning(t('dna.dialects.page.warning.selectSources', 'Select dialect sources to delete.'));
      return;
    }
    setSaving(true);
    try {
      await request(`${baseConfig.baseUrl}/sources?ids=${encodeURIComponent(selectedSourceIds.join(','))}`, {
        method: 'DELETE',
      });
      message.success(t('dna.dialects.page.success.delete', 'Dialect sources deleted successfully.'));
      setSelectedSourceIds([]);
      await reload();
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.dialects.page.error.delete', 'Failed to delete dialect sources.')));
    } finally {
      setSaving(false);
    }
  }, [baseConfig.baseUrl, reload, selectedSourceIds, t]);

  const dialectColumns = useMemo<ColumnsType<DialectDescriptor>>(() => [
    {
      title: t('dna.dialects.page.table.dialects.name', 'Dialect'),
      dataIndex: 'name',
      key: 'name',
      width: 220,
      render: (_value, record) => (
        <div>
          <div>{record.name}</div>
          <Text type="secondary" style={{ fontSize: 12 }}>{record.code}</Text>
        </div>
      ),
    },
    {
      title: t('dna.dialects.page.table.dialects.source', 'Source'),
      dataIndex: 'sourceType',
      key: 'sourceType',
      width: 120,
      render: (value: SourceType) => <Tag color={sourceTypeColorMap[value]}>{resolveSourceTypeLabel(value)}</Tag>,
    },
    {
      title: t('dna.dialects.page.table.dialects.description', 'Description'),
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (value?: string | null) => value || '-',
    },
    {
      title: t('dna.dialects.page.table.dialects.version', 'Version'),
      dataIndex: 'version',
      key: 'version',
      width: 140,
      render: (value?: string | null) => value || '-',
    },
    {
      title: t('dna.dialects.page.table.dialects.boundDrivers', 'Bound Drivers'),
      dataIndex: 'boundDriverCodes',
      key: 'boundDriverCodes',
      width: 240,
      render: (value?: string[] | null) => value && value.length > 0
        ? <Space wrap>{value.map((code) => <Tag key={code}>{code}</Tag>)}</Space>
        : '-',
    },
    {
      title: t('dna.dialects.page.table.dialects.className', 'Implementation Class'),
      dataIndex: 'className',
      key: 'className',
      width: 280,
      ellipsis: true,
      render: (value?: string | null) => value || '-',
    },
    {
      title: t('dna.dialects.page.table.dialects.sourceInfo', 'Source Info'),
      dataIndex: 'sourceName',
      key: 'sourceName',
      width: 260,
      render: (_value, record) => (
        <div>
          <div>{record.sourceName || '-'}</div>
          {record.sourceUrl ? <Text type="secondary" style={{ fontSize: 12 }}>{record.sourceUrl}</Text> : null}
          {record.localJarPath ? <Text type="secondary" style={{ display: 'block', fontSize: 12 }}>{record.localJarPath}</Text> : null}
        </div>
      ),
    },
    {
      title: t('dna.dialects.page.table.dialects.status', 'Load Status'),
      dataIndex: 'lastLoadMessage',
      key: 'lastLoadMessage',
      width: 220,
      ellipsis: true,
      render: (value?: string | null) => value || '-',
    },
    {
      title: t('dna.dialects.page.table.dialects.loadedAt', 'Loaded At'),
      dataIndex: 'loadedAt',
      key: 'loadedAt',
      width: 180,
      render: (value?: string | null) => formatDateTime(value),
    },
  ], [resolveSourceTypeLabel, t]);

  const sourceColumns = useMemo<ColumnsType<DialectSourceSummary>>(() => [
    {
      title: t('dna.dialects.page.table.sources.name', 'Dialect Source'),
      dataIndex: 'name',
      key: 'name',
      width: 220,
    },
    {
      title: t('dna.dialects.page.table.sources.type', 'Type'),
      dataIndex: 'sourceType',
      key: 'sourceType',
      width: 120,
      render: (value: SourceType) => <Tag color={sourceTypeColorMap[value]}>{resolveSourceTypeLabel(value)}</Tag>,
    },
    {
      title: t('dna.dialects.page.table.sources.enabled', 'Enabled'),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 100,
      render: (value?: boolean | null) => (
        <Tag color={value === false ? 'red' : 'green'}>
          {value === false ? t('dna.dialects.page.state.no', 'No') : t('dna.dialects.page.state.yes', 'Yes')}
        </Tag>
      ),
    },
    {
      title: t('dna.dialects.page.table.sources.sourceUrl', 'URL'),
      dataIndex: 'sourceUrl',
      key: 'sourceUrl',
      width: 260,
      ellipsis: true,
      render: (value?: string | null) => value || '-',
    },
    {
      title: t('dna.dialects.page.table.sources.localJarPath', 'Local Jar'),
      dataIndex: 'localJarPath',
      key: 'localJarPath',
      width: 280,
      ellipsis: true,
      render: (value?: string | null) => value || '-',
    },
    {
      title: t('dna.dialects.page.table.sources.discoveredDialects', 'Discovered Dialects'),
      dataIndex: 'discoveredDialectCodes',
      key: 'discoveredDialectCodes',
      width: 220,
      render: (value?: string[] | null) => value && value.length > 0
        ? <Space wrap>{value.map((code) => <Tag key={code}>{code}</Tag>)}</Space>
        : '-',
    },
    {
      title: t('dna.dialects.page.table.sources.description', 'Description'),
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (value?: string | null) => value || '-',
    },
    {
      title: t('dna.dialects.page.table.sources.lastResult', 'Last Result'),
      dataIndex: 'lastLoadMessage',
      key: 'lastLoadMessage',
      width: 220,
      ellipsis: true,
      render: (value?: string | null) => value || '-',
    },
    {
      title: t('dna.dialects.page.table.sources.loadedAt', 'Loaded At'),
      dataIndex: 'loadedAt',
      key: 'loadedAt',
      width: 180,
      render: (value?: string | null) => formatDateTime(value),
    },
  ], [resolveSourceTypeLabel, t]);

  return (
    <>
      <Tabs
        items={[
          {
            key: 'dialects',
            label: t('dna.dialects.page.tab.loadedDialects', 'Loaded Dialects'),
            children: (
              <>
                <Alert
                  type="info"
                  showIcon
                  style={{ marginBottom: 16 }}
                  message={t('dna.dialects.page.alert.loaded.title', 'Unified dialect source view')}
                  description={t(
                    'dna.dialects.page.alert.loaded.description',
                    'This view shows classpath dialects from project dependencies, dialects loaded from remote URLs, and dialects loaded from uploaded jars, together with their automatically bound drivers.',
                  )}
                />
                <Table<DialectDescriptor>
                  rowKey={(record) => `${record.code}-${record.className}-${record.sourceId || 'classpath'}`}
                  loading={loading}
                  columns={dialectColumns}
                  dataSource={loadedDialects}
                  pagination={{ pageSize: 10, showSizeChanger: true }}
                  scroll={{ x: 1600 }}
                />
              </>
            ),
          },
          {
            key: 'sources',
            label: t('dna.dialects.page.tab.externalSources', 'External Dialect Sources'),
            children: (
              <>
                <Space style={{ marginBottom: 16 }}>
                  <Button type="primary" icon={<PlusOutlined />} onClick={openUrlModal}>
                    {t('dna.dialects.page.button.createUrl', 'Create URL Source')}
                  </Button>
                  <Button onClick={openUploadModal}>{t('dna.dialects.page.button.uploadJar', 'Upload Dialect Jar')}</Button>
                  <Button danger disabled={selectedSourceIds.length === 0} loading={saving} onClick={() => void handleDeleteSources()}>
                    {t('dna.dialects.page.button.deleteSelected', 'Delete Selected')}
                  </Button>
                </Space>
                <Table<DialectSourceSummary>
                  rowKey="id"
                  loading={sourceLoading}
                  rowSelection={{
                    selectedRowKeys: selectedSourceIds,
                    onChange: (keys) => setSelectedSourceIds(keys),
                  }}
                  columns={sourceColumns}
                  dataSource={sources}
                  pagination={{ pageSize: 10, showSizeChanger: true }}
                  scroll={{ x: 1600 }}
                />
              </>
            ),
          },
        ]}
      />

      <Modal
        title={t('dna.dialects.page.modal.url.title', 'Create URL Dialect Source')}
        open={urlModalOpen}
        destroyOnHidden
        onCancel={() => !saving && setUrlModalOpen(false)}
        onOk={() => void handleCreateUrlSource()}
        confirmLoading={saving}
        okText={t('dna.dialects.page.modal.url.ok', 'Create')}
        cancelText={t('cancel', 'Cancel')}
      >
        <Form form={urlForm} layout="vertical" initialValues={{ enabled: true }}>
          <Form.Item
            label={t('dna.dialects.page.form.name.label', 'Source Name')}
            name="name"
            rules={[{ required: true, message: t('dna.dialects.page.form.name.required', 'Enter the source name.') }]}
          >
            <Input maxLength={128} placeholder={t('dna.dialects.page.form.name.placeholder.url', 'For example PostgreSQL extended dialect')} />
          </Form.Item>
          <Form.Item
            label={t('dna.dialects.page.form.sourceUrl.label', 'Remote URL')}
            name="sourceUrl"
            rules={[{ required: true, message: t('dna.dialects.page.form.sourceUrl.required', 'Enter the remote URL.') }]}
          >
            <Input maxLength={2048} placeholder={t('dna.dialects.page.form.sourceUrl.placeholder', 'https://example.com/custom-dialect.jar')} />
          </Form.Item>
          <Form.Item label={t('dna.dialects.page.form.description.label', 'Description')} name="description">
            <Input.TextArea maxLength={512} placeholder={t('dna.dialects.page.form.description.placeholder', 'Optional note')} />
          </Form.Item>
          <Form.Item label={t('dna.dialects.page.form.enabled.label', 'Enabled')} name="enabled" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('dna.dialects.page.modal.upload.title', 'Upload Dialect Jar')}
        open={uploadModalOpen}
        destroyOnHidden
        onCancel={() => !saving && setUploadModalOpen(false)}
        onOk={() => void handleUploadSource()}
        confirmLoading={saving}
        okText={t('dna.dialects.page.modal.upload.ok', 'Upload')}
        cancelText={t('cancel', 'Cancel')}
      >
        <Paragraph type="secondary">
          {t(
            'dna.dialects.page.description.uploadIntro',
            'After upload, the database dialect implementations inside the jar are discovered automatically through SPI / ServiceLoader.',
          )}
        </Paragraph>
        <Form form={uploadForm} layout="vertical" initialValues={{ enabled: true }}>
          <Form.Item
            label={t('dna.dialects.page.form.name.label', 'Source Name')}
            name="name"
            rules={[{ required: true, message: t('dna.dialects.page.form.name.required', 'Enter the source name.') }]}
          >
            <Input maxLength={128} placeholder={t('dna.dialects.page.form.name.placeholder.upload', 'For example internal enterprise dialect')} />
          </Form.Item>
          <Form.Item label={t('dna.dialects.page.form.description.label', 'Description')} name="description">
            <Input.TextArea maxLength={512} placeholder={t('dna.dialects.page.form.description.placeholder', 'Optional note')} />
          </Form.Item>
          <Form.Item label={t('dna.dialects.page.form.enabled.label', 'Enabled')} name="enabled" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item
            label={t('dna.dialects.page.form.jar.label', 'Dialect Jar')}
            name="file"
            valuePropName="fileList"
            getValueFromEvent={normalizeUploadEvent}
            rules={[{ required: true, message: t('dna.dialects.page.form.jar.required', 'Upload a dialect jar.') }]}
          >
            <Dragger beforeUpload={() => false} multiple={false} maxCount={1} accept=".jar">
              <p className="ant-upload-drag-icon"><InboxOutlined /></p>
              <p className="ant-upload-text">{t('dna.dialects.page.form.jar.dragText', 'Click or drag a dialect jar here to upload')}</p>
              <p className="ant-upload-hint">{t('dna.dialects.page.form.jar.hint', 'Only one jar file is supported')}</p>
            </Dragger>
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
};

export default App;
