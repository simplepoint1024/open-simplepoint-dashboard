import api from '@/api';
import {InboxOutlined} from '@simplepoint/shared/types/icon';
import type {TableButtonProps} from '@simplepoint/components/Table';
import SimpleTable from '@simplepoint/components/SimpleTable';
import TableActions from '@simplepoint/components/Table/Actions';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import type {RJSFSchema} from '@rjsf/utils';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {request} from '@simplepoint/shared/api/client';
import {post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, App as AntApp, Modal, Spin, Typography, Upload} from 'antd';
import type {UploadFile} from 'antd/es/upload/interface';
import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {formatDateTime, resolveErrorMessage} from '../shared';

const {Dragger} = Upload;
const {Paragraph, Text} = Typography;
const baseConfig = api['platform.dna-drivers'];

type JdbcDriverRow = {
  id?: string;
  code?: string;
  name?: string;
};

type JdbcDriverArtifactResult = {
  id?: string;
  code?: string;
  driverCode?: string;
  localJarPath?: string;
  driverClassName?: string;
  jdbcUrlPattern?: string;
  version?: string;
  downloadedAt?: string;
  lastDownloadMessage?: string;
  message?: string;
};

type UploadFormValues = {
  name?: string;
  code?: string;
  databaseType?: string;
  downloadUrl?: string;
  enabled?: boolean;
  description?: string;
};

const App = () => {
  const {t, ensure, locale} = useI18n();
  const {message} = AntApp.useApp();
  const operation = useOperationScope();
  const [writeError, setWriteError] = useState('');
  const [artifact, setArtifact] = useState<{result: JdbcDriverArtifactResult; label?: string}>();
  const [tableKey, setTableKey] = useState(0);
  const uploadFormRef = useRef<SFormHandle>(null);
  const [uploadValues, setUploadValues] = useState<UploadFormValues>({enabled: true});
  const [files, setFiles] = useState<UploadFile[]>([]);
  const uploadingRef = useRef(false);
  const [uploading, setUploading] = useState(false);
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [uploadMode, setUploadMode] = useState<'create' | 'replace'>('create');
  const [uploadTarget, setUploadTarget] = useState<JdbcDriverRow | null>(null);
  const uploadPolicy = useSchema(baseConfig.baseUrl, {staleTime: 30_000, retry: false});
  const canUpload = !uploadPolicy.error && Boolean(uploadPolicy.data?.buttons.some(button => button.key === 'upload'));
  const canDownload = !uploadPolicy.error && Boolean(uploadPolicy.data?.buttons.some(button => button.key === 'download'));
  const writable = useCallback((field: string) => {
    const property = uploadPolicy.data?.schema.properties?.[field];
    return property && typeof property === 'object' && !property.readOnly ? property : undefined;
  }, [uploadPolicy.data?.schema]);
  const createFieldsAvailable = ['name', 'code', 'databaseType'].every(field => writable(field));
  const locked = uploadModalOpen || operation.busy || !!artifact;
  usePageRetention(!locked, uploadModalOpen || operation.busy);

  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [ensure, locale]);

  const refreshTable = useCallback(() => {
    setTableKey((value) => value + 1);
  }, []);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema?.properties ?? {};
    delete properties.version;
    delete properties.driverClassName;
    delete properties.jdbcUrlPattern;
    delete properties.localJarPath;
    delete properties.downloadedAt;
    delete properties.lastDownloadMessage;
    if (properties.downloadUrl) {
      properties.downloadUrl.description = t(
        'dna.drivers.page.downloadUrl.description',
        '保存时会自动下载驱动 JAR；如需直接导入本地 JAR，请使用“上传驱动”按钮',
      );
    }
    return nextSchema;
  }, [t]);

  const columnOverrides = {
    downloadUrl: {
      width: 260,
      ellipsis: true,
    },
    driverClassName: {
      width: 280,
      ellipsis: true,
    },
    jdbcUrlPattern: {
      width: 260,
      ellipsis: true,
    },
    localJarPath: {
      width: 280,
      ellipsis: true,
    },
    lastDownloadMessage: {
      width: 220,
      ellipsis: true,
    },
  };

  const artifactContent = artifact && (() => {
    const {result, label: fallbackDriverLabel} = artifact;
    return (
        <div style={{marginTop: 16}}>
          <Paragraph>
            <Text strong>{t('dna.drivers.page.field.driver', '驱动')}：</Text>
            {result.driverCode || result.code || fallbackDriverLabel || '-'}
          </Paragraph>
          <Paragraph>
            <Text strong>{t('dna.drivers.page.field.localJarPath', '本地路径')}：</Text>
            <Text code>{result.localJarPath || '-'}</Text>
          </Paragraph>
          <Paragraph>
            <Text strong>{t('dna.drivers.page.field.driverClassName', '驱动类')}：</Text>
            <Text code>{result.driverClassName || '-'}</Text>
          </Paragraph>
          <Paragraph>
            <Text strong>{t('dna.drivers.page.field.jdbcUrlPattern', 'JDBC URL 规则')}：</Text>
            <Text code>{result.jdbcUrlPattern || '-'}</Text>
          </Paragraph>
          <Paragraph>
            <Text strong>{t('dna.drivers.page.field.version', '驱动版本')}：</Text>
            {result.version || '-'}
          </Paragraph>
          <Paragraph>
            <Text strong>{t('dna.drivers.page.field.downloadedAt', '导入时间')}：</Text>
            {formatDateTime(result.downloadedAt)}
          </Paragraph>
          <Paragraph style={{marginBottom: 0}}>
            <Text strong>{t('dna.drivers.page.field.result', '结果')}：</Text>
            {result.message || result.lastDownloadMessage || t('dna.drivers.page.state.completed', '导入完成')}
          </Paragraph>
        </div>
    );
  })();

  const handleDownload = useCallback(async (_keys: React.Key[], rows: JdbcDriverRow[]) => {
    if (locked || !canDownload) return;
    const driver = rows?.[0];
    if (!driver?.id) {
      message.warning(t('dna.drivers.page.warning.selectDriver', '请选择要下载的驱动'));
      return;
    }

    const captured = structuredClone(driver), controller = operation.begin(); if (!controller) return;
    setWriteError('');
    try {
      const result = await post<JdbcDriverArtifactResult>(`${baseConfig.baseUrl}/${encodeURIComponent(captured.id!)}/download`, {}, {signal: controller.signal});
      if (!operation.current(controller.signal)) return;
      message.success(t('dna.drivers.page.success.download', '驱动下载成功'));
      setArtifact({result, label: captured.code || captured.name});
      refreshTable();
    } catch (error) {
      if (operation.current(controller.signal)) setWriteError(resolveErrorMessage(error, t('dna.drivers.page.error.download', '驱动下载失败')));
    } finally {operation.finish(controller);}
  }, [locked, canDownload, message, refreshTable, t, operation.begin, operation.current, operation.finish]);

  const closeUploadModal = useCallback(() => {
    if (uploadingRef.current || operation.busy) {
      return;
    }
    setUploadModalOpen(false);
    setUploadTarget(null);
    setUploadValues({enabled: true}); setFiles([]); setWriteError('');
  }, [operation.busy]);

  const openUploadModal = useCallback((_keys: React.Key[], rows: JdbcDriverRow[]) => {
    if (!canUpload || uploadingRef.current || locked || !operation.isSelected()) return;
    setWriteError('');
    const driver = rows?.[0];
    setUploadValues({enabled: true}); setFiles([]);
    if (driver?.id) {
      setUploadMode('replace');
      setUploadTarget(structuredClone(driver));
    } else {
      setUploadMode('create');
      setUploadTarget(null);
    }
    setUploadModalOpen(true);
  }, [canUpload, locked, operation.isSelected]);

  const handleUpload = useCallback(async (values: UploadFormValues) => {
    if (!canUpload || !uploadModalOpen || uploadingRef.current || uploadMode === 'create' && !createFieldsAvailable) return;
    const targetFile = files[0]?.originFileObj as File | undefined;
    if (!targetFile) {
      message.warning(t('dna.drivers.page.warning.selectJar', '请先选择驱动 JAR 文件'));
      return;
    }
    const formData = new FormData();
    formData.append('file', targetFile);
    let url = `${baseConfig.baseUrl}/upload`;
    if (uploadMode === 'create') {
      formData.append('name', values.name?.trim() || '');
      formData.append('code', values.code?.trim() || '');
      formData.append('databaseType', values.databaseType?.trim() || '');
      if (writable('enabled')) formData.append('enabled', String(values.enabled !== false));
      if (writable('downloadUrl') && values.downloadUrl?.trim()) {
        formData.append('downloadUrl', values.downloadUrl.trim());
      }
      if (writable('description') && values.description?.trim()) {
        formData.append('description', values.description.trim());
      }
    } else {
      if (!uploadTarget?.id) {
        message.warning(t('dna.drivers.page.warning.selectUploadTarget', '请选择要上传的驱动'));
        return;
      }
      url = `${baseConfig.baseUrl}/${encodeURIComponent(uploadTarget.id)}/upload`;
    }
    const controller = operation.begin(); if (!controller) return;
    setWriteError('');
    uploadingRef.current = true; setUploading(true);
    try {
      const result = await request<JdbcDriverArtifactResult>(url, {
        method: 'POST',
        body: formData,
        signal: controller.signal,
      });
      if (!operation.current(controller.signal)) return;
      message.success(uploadMode === 'create'
        ? t('dna.drivers.page.success.uploadCreate', '驱动上传并创建成功')
        : t('dna.drivers.page.success.uploadReplace', '驱动上传成功'));
      setUploadModalOpen(false); setFiles([]); setUploadValues({enabled: true}); setUploadTarget(null);
      setArtifact({result, label: uploadTarget?.code || uploadTarget?.name || values.code});
      refreshTable();
    } catch (error) {
      if (operation.current(controller.signal)) setWriteError(resolveErrorMessage(error, t('dna.drivers.page.error.upload', '驱动上传失败')));
    } finally {
      uploadingRef.current = false; operation.finish(controller); if (operation.current()) setUploading(false);
    }
  }, [canUpload, createFieldsAvailable, files, message, refreshTable, t, uploadMode, uploadTarget, uploadModalOpen, writable,
    operation.begin, operation.current, operation.finish]);

  const customButtonEvents: Record<string, (selectedRowKeys: React.Key[], selectedRows: JdbcDriverRow[],
    props: TableButtonProps) => void> = {
    download: (selectedRowKeys, selectedRows) => {
      void handleDownload(selectedRowKeys, selectedRows);
    },
    upload: (selectedRowKeys, selectedRows) => {
      openUploadModal(selectedRowKeys, selectedRows);
    },
  };

  const uploadSchema = useMemo<RJSFSchema>(() => ({type: 'object', required: ['name', 'code', 'databaseType'],
    properties: Object.fromEntries([
      ['name', t('dna.drivers.page.form.name.label', '驱动名称')],
      ['code', t('dna.drivers.page.form.code.label', '驱动编码')],
      ['databaseType', t('dna.drivers.page.form.databaseType.label', '数据库类型')],
      ['downloadUrl', t('dna.drivers.page.form.downloadUrl.label', '下载地址')],
      ['description', t('dna.drivers.page.form.description.label', '描述')],
      ['enabled', t('dna.drivers.page.form.enabled.label', '是否启用')],
    ].flatMap(([field, title]) => {
      const property = writable(field);
      return property ? [[field, {...property, title,
        ...(['name', 'code', 'databaseType'].includes(field) ? {minLength: 1, pattern: '\\S'} : {}),
        ...(field === 'description' ? {'x-ui': {widget: 'textarea'}} : {}),
      }]] : [];
    })),
  }), [t, writable]);

  return (
    <>
      {writeError && !uploadModalOpen && <Alert showIcon type="error" title={writeError}/>}
      {operation.busy && !uploadModalOpen && <Alert showIcon type="info" title={t('dna.drivers.page.progress.downloading', '正在下载驱动...')}/>}
      <SimpleTable
        key={tableKey}
        {...baseConfig}
        formSchemaTransform={formSchemaTransform}
        columnOverrides={columnOverrides}
        customButtonEvents={customButtonEvents}
        isButtonDisabled={() => locked || !operation.selected}
        rowSelection={{isRowSelectable: () => !locked}}
      />
      <Modal
        wrapClassName="dna-driver-upload"
        title={uploadMode === 'create'
          ? t('dna.drivers.page.modal.createTitle', '上传并新增驱动')
          : t('dna.drivers.page.modal.replaceTitle', '上传驱动包')}
        open={uploadModalOpen && operation.selected}
        destroyOnHidden={!uploadModalOpen}
        okText={uploadMode === 'create'
          ? t('dna.drivers.page.modal.createOk', '上传并创建')
          : t('dna.drivers.page.modal.replaceOk', '上传并替换')}
        cancelText={t('cancel', '取消')}
        confirmLoading={uploading}
        onCancel={closeUploadModal}
        onOk={() => uploadMode === 'create' ? uploadFormRef.current?.submit() : void handleUpload({})}
        closable={!uploading} maskClosable={!uploading} keyboard={!uploading}
        cancelButtonProps={{disabled: uploading}}
        okButtonProps={{disabled: !canUpload || uploadMode === 'create' && !createFieldsAvailable}}
      >
        {writeError && <Alert showIcon type="error" title={writeError}/>}
        {uploadMode === 'create' ? (
          <Alert
            type="info"
            closable
            showIcon
            style={{marginBottom: 16}}
            message={t('dna.drivers.page.modal.createAlert.title', '上传本地驱动 JAR')}
            description={t(
              'dna.drivers.page.modal.createAlert.description',
              '上传后会自动识别驱动类、版本和 JDBC URL 规则。下载地址可选，用于后续远程重新下载驱动。',
            )}
          />
        ) : (
          <Alert
            type="info"
            closable
            showIcon
            style={{marginBottom: 16}}
            message={t('dna.drivers.page.modal.replaceAlert.title', '替换已有驱动包')}
            description={t(
              'dna.drivers.page.modal.replaceAlert.description',
              '重新上传后会覆盖本地驱动文件，并自动刷新驱动类、版本和 JDBC URL 规则。',
            )}
          />
        )}
        <Spin spinning={uploadPolicy.isPending}>
          {uploadPolicy.error && <Alert type="error" showIcon title={t('table.loadFail', '加载失败')}
            description={resolveErrorMessage(uploadPolicy.error, t('table.loadFail', '加载失败'))}/>}
          {uploadMode === 'create' ? (
            createFieldsAvailable ? <SForm ref={uploadFormRef} schema={uploadSchema} formData={uploadValues}
              hideSubmit disabled={uploading} onChange={event => {if (!uploadingRef.current) setUploadValues(event.formData ?? {});}}
              onSubmit={event => void handleUpload(event.formData ?? {})}/>
              : !uploadPolicy.isPending && <Alert type="warning" showIcon title={t('table.actionUnavailable', '此操作暂不可用，请刷新页面后重试')}/>
          ) : (
            <div style={{marginBottom: 16}}>
              <Paragraph>
                <Text strong>{t('dna.drivers.page.field.driver', '驱动')}：</Text>
                {uploadTarget?.name || uploadTarget?.code || '-'}
              </Paragraph>
              <Paragraph style={{marginBottom: 0}}>
                <Text strong>{t('dna.drivers.page.field.code', '编码')}：</Text>
                {uploadTarget?.code || '-'}
              </Paragraph>
            </div>
          )}
          <Text>{t('dna.drivers.page.form.jar.label', '驱动 JAR')}</Text>
          {/* A local driver artifact uploads directly to DNA, not through OSS attachment widgets. */}
            <Dragger disabled={uploading || !canUpload} fileList={files} onChange={event => {if (!uploadingRef.current && operation.isSelected()) setFiles(event.fileList);}}
              beforeUpload={() => false} multiple={false} maxCount={1} name="file" accept=".jar">
              <p className="ant-upload-drag-icon">
                <InboxOutlined />
              </p>
              <p className="ant-upload-text">{t('dna.drivers.page.form.jar.dragText', '点击或拖拽驱动 JAR 到此处上传')}</p>
              <p className="ant-upload-hint">{t('dna.drivers.page.form.jar.hint', '仅支持单个 JDBC 驱动 JAR 文件')}</p>
            </Dragger>
        </Spin>
      </Modal>
      <Modal wrapClassName="dna-driver-artifact" open={!!artifact && operation.selected} destroyOnHidden={!artifact}
        title={t('dna.drivers.page.importResult.title', '驱动导入完成')} onCancel={() => setArtifact(undefined)}
        footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'close', sort: 1, text: t('dna.drivers.page.action.close', '关闭'), type: 'primary'},
        ]} onButtonEvents={{close: () => setArtifact(undefined)}}/>}>
        {artifactContent}
      </Modal>
    </>
  );
};

export default function ScopedJdbcDrivers() {
  const scope = useQueryScope();
  return <App key={JSON.stringify(scope)}/>;
}
