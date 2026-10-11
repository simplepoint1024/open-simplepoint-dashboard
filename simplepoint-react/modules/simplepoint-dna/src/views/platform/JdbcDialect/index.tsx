import api from '@/api';
import { InboxOutlined, PlusOutlined } from '@simplepoint/shared/types/icon';
import { request } from '@simplepoint/shared/api/client';
import { get, useData } from '@simplepoint/shared/api/methods';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import { useI18n } from '@simplepoint/shared/hooks/useI18n';
import {Alert, App as AntApp, Modal, Space, Tabs, Tag, Typography, Upload} from 'antd';
import Table from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import type {RJSFSchema} from '@rjsf/utils';
import type { ColumnsType } from 'antd/es/table';
import type { UploadFile } from 'antd/es/upload/interface';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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

const sourceTypeColorMap: Record<SourceType, string> = {
  CLASSPATH: 'blue',
  URL: 'gold',
  UPLOAD: 'purple',
};

const App = () => {
  const { t, ensure, locale } = useI18n();
  const { message } = AntApp.useApp();
  const operation = useOperationScope();
  const [writeError, setWriteError] = useState('');
  const [deleting, setDeleting] = useState<{id: string; name: string}[]>();
  const [urlModalOpen, setUrlModalOpen] = useState(false);
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [selectedSourceIds, setSelectedSourceIds] = useState<React.Key[]>([]);
  const [urlValues, setUrlValues] = useState<UrlSourceFormValues>({enabled: true});
  const [uploadValues, setUploadValues] = useState<UploadSourceFormValues>({enabled: true});
  const [files, setFiles] = useState<UploadFile[]>([]);
  const urlFormRef = useRef<SFormHandle>(null);
  const uploadFormRef = useRef<SFormHandle>(null);
  const savingRef = useRef(false);
  const locked = urlModalOpen || uploadModalOpen || !!deleting || operation.busy;
  usePageRetention(!locked, locked);
  const permissions = useData(['dna-dialect-permissions', baseConfig.baseUrl], ({signal}) =>
    get<{canCreateUrl: boolean; canUpload: boolean; canDelete: boolean}>(`${baseConfig.baseUrl}/workbench-permissions`, undefined, {signal}), {retry: false});
  const dialectsQuery = useData(['dna-dialects', baseConfig.baseUrl], ({signal}) =>
    get<DialectDescriptor[]>(baseConfig.baseUrl, undefined, {signal}), {retry: false});
  const sourcesQuery = useData(['dna-dialect-sources', baseConfig.baseUrl], ({signal}) =>
    get<DialectSourceSummary[]>(`${baseConfig.baseUrl}/sources`, undefined, {signal}), {retry: false});
  const loadedDialects = dialectsQuery.data ?? [];
  const sources = sourcesQuery.data ?? [];
  const loading = dialectsQuery.isFetching;
  const sourceLoading = sourcesQuery.isFetching;
  const loadError = permissions.error || dialectsQuery.error || sourcesQuery.error;

  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [ensure, locale]);

  const resolveSourceTypeLabel = useCallback((value: SourceType) => {
    return t(`dna.dialects.page.sourceType.${value}`, value);
  }, [message, t]);

  const reload = useCallback(async () => {
    await Promise.all([dialectsQuery.refetch(), sourcesQuery.refetch()]);
  }, [dialectsQuery.refetch, sourcesQuery.refetch]);

  const openUrlModal = useCallback(() => {
    if (!permissions.data?.canCreateUrl || locked || loadError || !operation.isSelected()) return;
    setWriteError('');
    setUrlValues({enabled: true});
    setUrlModalOpen(true);
  }, [permissions.data?.canCreateUrl, locked, loadError, operation.isSelected]);

  const openUploadModal = useCallback(() => {
    if (!permissions.data?.canUpload || locked || loadError || !operation.isSelected()) return;
    setWriteError('');
    setUploadValues({enabled: true}); setFiles([]);
    setUploadModalOpen(true);
  }, [permissions.data?.canUpload, locked, loadError, operation.isSelected]);

  const handleCreateUrlSource = useCallback(async (values: UrlSourceFormValues) => {
    if (!permissions.data?.canCreateUrl || !urlModalOpen || loadError || savingRef.current) return;
    const controller = operation.begin(); if (!controller) return; setWriteError('');
    savingRef.current = true;
    setSaving(true);
    try {
      await request(`${baseConfig.baseUrl}/url`, {
        method: 'POST',
        signal: controller.signal,
        body: JSON.stringify({
          name: values.name?.trim(),
          sourceUrl: values.sourceUrl?.trim(),
          enabled: values.enabled !== false,
          description: values.description?.trim() || null,
        }),
      });
      if (!operation.current(controller.signal)) return;
      message.success(t('dna.dialects.page.success.createUrl', 'URL dialect source created successfully.'));
      setUrlModalOpen(false);
      setUrlValues({enabled: true});
      await reload();
    } catch (error) {
      if (operation.current(controller.signal)) setWriteError(resolveErrorMessage(error, t('dna.dialects.page.error.createUrl', 'Failed to create URL dialect source.')));
    } finally {
      savingRef.current = false; operation.finish(controller); if (operation.current()) setSaving(false);
    }
  }, [message, reload, t, permissions.data?.canCreateUrl, urlModalOpen, loadError, operation.begin, operation.current, operation.finish]);

  const handleUploadSource = useCallback(async (values: UploadSourceFormValues) => {
    if (!permissions.data?.canUpload || !uploadModalOpen || loadError || savingRef.current) return;
    const file = files[0]?.originFileObj as File | undefined;
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
    const controller = operation.begin(); if (!controller) return; setWriteError('');
    savingRef.current = true; setSaving(true);
    try {
      await request(`${baseConfig.baseUrl}/upload`, {
        method: 'POST',
        body: formData,
        signal: controller.signal,
      });
      if (!operation.current(controller.signal)) return;
      message.success(t('dna.dialects.page.success.upload', 'Dialect jar uploaded successfully.'));
      setUploadModalOpen(false);
      setUploadValues({enabled: true}); setFiles([]);
      await reload();
    } catch (error) {
      if (operation.current(controller.signal)) setWriteError(resolveErrorMessage(error, t('dna.dialects.page.error.upload', 'Failed to upload dialect jar.')));
    } finally {
      savingRef.current = false; operation.finish(controller); if (operation.current()) setSaving(false);
    }
  }, [message, reload, t, files, permissions.data?.canUpload, uploadModalOpen, loadError, operation.begin, operation.current, operation.finish]);

  const handleDeleteSources = useCallback(async () => {
    if (!permissions.data?.canDelete || !deleting?.length || savingRef.current || loadError) return;
    const captured = deleting.map(source => source.id);
    const controller = operation.begin(); if (!controller) return; setWriteError('');
    if (selectedSourceIds.length === 0) {
      message.warning(t('dna.dialects.page.warning.selectSources', 'Select dialect sources to delete.'));
      return;
    }
    savingRef.current = true; setSaving(true);
    try {
      await request(`${baseConfig.baseUrl}/sources?ids=${encodeURIComponent(captured.join(','))}`, {
        method: 'DELETE',
        signal: controller.signal,
      });
      if (!operation.current(controller.signal)) return;
      message.success(t('dna.dialects.page.success.delete', 'Dialect sources deleted successfully.'));
      setSelectedSourceIds([]);
      setDeleting(undefined);
      await reload();
    } catch (error) {
      if (operation.current(controller.signal)) setWriteError(resolveErrorMessage(error, t('dna.dialects.page.error.delete', 'Failed to delete dialect sources.')));
    } finally {
      savingRef.current = false; operation.finish(controller); if (operation.current()) setSaving(false);
    }
  }, [message, reload, selectedSourceIds, deleting, t, permissions.data?.canDelete, loadError, operation.begin, operation.current, operation.finish]);

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
        <Tag color={value === false ? 'red' : value === true ? 'green' : 'default'}>
          {value === false ? t('dna.dialects.page.state.no', 'No') : value === true ? t('dna.dialects.page.state.yes', 'Yes') : '—'}
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

  const sourceSchema = useMemo<RJSFSchema>(() => ({type: 'object', required: ['name'], properties: {
    name: {type: 'string', title: t('dna.dialects.page.form.name.label', 'Source Name'), minLength: 1, maxLength: 128, pattern: '\\S'},
    description: {type: 'string', title: t('dna.dialects.page.form.description.label', 'Description'), maxLength: 512,
      'x-ui': {widget: 'textarea'}},
    enabled: {type: 'boolean', title: t('dna.dialects.page.form.enabled.label', 'Enabled'), default: true},
  }}), [t]);
  const urlSchema = useMemo<RJSFSchema>(() => ({...sourceSchema, required: ['name', 'sourceUrl'], properties: {
    ...sourceSchema.properties, sourceUrl: {type: 'string', title: t('dna.dialects.page.form.sourceUrl.label', 'Remote URL'),
      minLength: 1, maxLength: 2048, format: 'uri', pattern: '^[hH][tT][tT][pP][sS]?://'},
  }}), [sourceSchema, t]);

  return (
    <>
      {loadError && <Alert type="error" showIcon title={t('table.loadFail', '加载失败')}
        description={resolveErrorMessage(loadError, t('dna.dialects.page.error.loadDialects', 'Failed to load dialects.'))}
        action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'retry', sort: 1, text: t('table.retry', '重试')},
        ]} onButtonEvents={{retry: () => {void reload(); void permissions.refetch();}}}/>}/>}
      <Tabs
        items={[
          {
            key: 'dialects',
            label: t('dna.dialects.page.tab.loadedDialects', 'Loaded Dialects'),
            children: (
              <>
                <Alert
                  type="info"
                  closable
                  showIcon
                  style={{ marginBottom: 16 }}
                  message={t('dna.dialects.page.alert.loaded.title', 'Unified dialect source view')}
                  description={t(
                    'dna.dialects.page.alert.loaded.description',
                    'This view shows classpath dialects from project dependencies, dialects loaded from remote URLs, and dialects loaded from uploaded jars, together with their automatically bound drivers.',
                  )}
                />
                <Table<DialectDescriptor>
                  storageKey="dna.dialects.loaded"
                  refresh={() => void reload()}
                  query={{mode: 'local', scope: 'complete', fields: {
                    code: {type: 'string'}, name: {type: 'string'}, description: {type: 'string'},
                    version: {type: 'string'}, className: {type: 'string'},
                    sourceType: {type: 'string', enum: ['CLASSPATH', 'URL', 'UPLOAD']},
                    sourceName: {type: 'string'}, enabled: {type: 'boolean'},
                    lastLoadMessage: {type: 'string'}, loadedAt: {type: 'date-time'},
                  }}}
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
                <Table<DialectSourceSummary>
                  storageKey="dna.dialects.sources"
                  refresh={() => void reload()}
                  toolbarExtra={<TableActions selectedKeys={selectedSourceIds}
                    selectedRows={sources.filter(source => selectedSourceIds.includes(source.id))}
                    buttons={[
                      ...(permissions.data?.canCreateUrl ? [{key: 'createUrl', sort: 1, type: 'primary' as const,
                        icon: <PlusOutlined/>, text: t('dna.dialects.page.button.createUrl', 'Create URL Source'), disabled: locked || !!loadError || !operation.selected}] : []),
                      ...(permissions.data?.canUpload ? [{key: 'upload', sort: 2,
                        text: t('dna.dialects.page.button.uploadJar', 'Upload Dialect Jar'), disabled: locked || !!loadError || !operation.selected}] : []),
                      ...(permissions.data?.canDelete ? [{key: 'delete', sort: 3, danger: true, argumentMinSize: 1,
                        text: t('dna.dialects.page.button.deleteSelected', 'Delete Selected'), disabled: locked || !!loadError || !operation.selected}] : []),
                    ]} onButtonEvents={{createUrl: openUrlModal, upload: openUploadModal,
                      delete: () => {
                        if (locked || loadError || !permissions.data?.canDelete || !operation.isSelected()) return;
                        const captured = sources.filter(source => selectedSourceIds.includes(source.id)).map(({id, name}) => ({id, name}));
                        if (captured.length !== selectedSourceIds.length || !captured.length) return;
                        setWriteError(''); setDeleting(captured);
                      }}}/>}
                  query={{mode: 'local', scope: 'complete', fields: {
                    name: {type: 'string'}, sourceType: {type: 'string', enum: ['URL', 'UPLOAD']},
                    sourceUrl: {type: 'string'}, localJarPath: {type: 'string'}, enabled: {type: 'boolean'},
                    description: {type: 'string'}, lastLoadMessage: {type: 'string'}, loadedAt: {type: 'date-time'},
                  }}}
                  rowKey="id"
                  loading={sourceLoading}
                  rowSelection={{
                    selectedRowKeys: selectedSourceIds,
                    onChange: (keys) => {if (!locked) setSelectedSourceIds(keys);},
                    getCheckboxProps: () => ({disabled: locked}),
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
        wrapClassName="dna-dialect-url"
        title={t('dna.dialects.page.modal.url.title', 'Create URL Dialect Source')}
        open={urlModalOpen && operation.selected}
        destroyOnHidden={!urlModalOpen}
        onCancel={() => !savingRef.current && setUrlModalOpen(false)}
        onOk={() => urlFormRef.current?.submit()}
        closable={!saving} maskClosable={!saving} keyboard={!saving} cancelButtonProps={{disabled: saving}}
        confirmLoading={saving}
        okText={t('dna.dialects.page.modal.url.ok', 'Create')}
        cancelText={t('cancel', 'Cancel')}
        okButtonProps={{disabled: !permissions.data?.canCreateUrl || !!loadError}}
      >
        {writeError && <Alert showIcon type="error" title={writeError}/>}
        <SForm ref={urlFormRef} schema={urlSchema} hideSubmit disabled={saving}
          formData={urlValues} onChange={event => {if (!savingRef.current) setUrlValues(event.formData ?? {});}}
          onSubmit={event => void handleCreateUrlSource(event.formData ?? {})}/>
      </Modal>

      <Modal
        wrapClassName="dna-dialect-upload"
        title={t('dna.dialects.page.modal.upload.title', 'Upload Dialect Jar')}
        open={uploadModalOpen && operation.selected}
        destroyOnHidden={!uploadModalOpen}
        onCancel={() => !savingRef.current && setUploadModalOpen(false)}
        onOk={() => uploadFormRef.current?.submit()}
        closable={!saving} maskClosable={!saving} keyboard={!saving} cancelButtonProps={{disabled: saving}}
        confirmLoading={saving}
        okText={t('dna.dialects.page.modal.upload.ok', 'Upload')}
        cancelText={t('cancel', 'Cancel')}
        okButtonProps={{disabled: !permissions.data?.canUpload || !!loadError}}
      >
        {writeError && <Alert showIcon type="error" title={writeError}/>}
        <Paragraph type="secondary">
          {t(
            'dna.dialects.page.description.uploadIntro',
            'After upload, the database dialect implementations inside the jar are discovered automatically through SPI / ServiceLoader.',
          )}
        </Paragraph>
        <SForm ref={uploadFormRef} schema={sourceSchema} hideSubmit disabled={saving}
          formData={uploadValues} onChange={event => {if (!savingRef.current) setUploadValues(event.formData ?? {});}}
          onSubmit={event => void handleUploadSource(event.formData ?? {})}/>
        <Text>{t('dna.dialects.page.form.jar.label', 'Dialect Jar')}</Text>
        {/* A local driver artifact uploads directly to the dialect endpoint, not through OSS attachment widgets. */}
            <Dragger disabled={saving || !permissions.data?.canUpload} fileList={files}
              onChange={event => {if (!savingRef.current && operation.isSelected()) setFiles(event.fileList);}}
              beforeUpload={() => false} multiple={false} maxCount={1} accept=".jar">
              <p className="ant-upload-drag-icon"><InboxOutlined /></p>
              <p className="ant-upload-text">{t('dna.dialects.page.form.jar.dragText', 'Click or drag a dialect jar here to upload')}</p>
              <p className="ant-upload-hint">{t('dna.dialects.page.form.jar.hint', 'Only one jar file is supported')}</p>
            </Dragger>
      </Modal>
      <Modal wrapClassName="dna-dialect-delete" open={!!deleting && operation.selected} destroyOnHidden={!deleting}
        title={t('dna.dialects.page.button.deleteSelected', 'Delete Selected')}
        closable={!saving} maskClosable={!saving} keyboard={!saving} onCancel={() => {if (!savingRef.current) setDeleting(undefined);}}
        footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'cancel', sort: 1, text: t('cancel', '取消'), disabled: saving},
          {key: 'confirm', sort: 2, text: t('action.confirm', '确认'), type: 'primary', danger: true, loading: saving,
            disabled: saving || !!loadError || !permissions.data?.canDelete},
        ]} onButtonEvents={{cancel: () => {if (!savingRef.current) setDeleting(undefined);}, confirm: () => void handleDeleteSources()}}/>}>
        {deleting?.map(source => source.name).join('、')}
        {writeError && <Alert showIcon type="error" title={writeError}/>}
      </Modal>
    </>
  );
};

export default function ScopedJdbcDialects() {
  const scope = useQueryScope();
  return <App key={JSON.stringify(scope)}/>;
}
