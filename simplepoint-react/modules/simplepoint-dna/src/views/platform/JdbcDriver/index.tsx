import api from '@/api';
import {InboxOutlined} from '@ant-design/icons';
import type {TableButtonProps} from '@simplepoint/components/Table';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {request} from '@simplepoint/shared/api/client';
import {post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, Form, Input, Modal, Switch, Typography, Upload, message} from 'antd';
import type {UploadFile} from 'antd/es/upload/interface';
import React, {useCallback, useEffect, useState} from 'react';
import {formatDateTime, resolveErrorMessage} from '../shared';

const {Dragger} = Upload;
const {Paragraph, Text} = Typography;
const {TextArea} = Input;
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
  file?: UploadFile[];
};

const normalizeUploadEvent = (event: {fileList?: UploadFile[]} | UploadFile[]) => {
  if (Array.isArray(event)) {
    return event;
  }
  return event?.fileList ?? [];
};

const App = () => {
  const {t, ensure, locale} = useI18n();
  const [tableKey, setTableKey] = useState(0);
  const [uploadForm] = Form.useForm<UploadFormValues>();
  const [uploading, setUploading] = useState(false);
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [uploadMode, setUploadMode] = useState<'create' | 'replace'>('create');
  const [uploadTarget, setUploadTarget] = useState<JdbcDriverRow | null>(null);

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

  const showArtifactResult = useCallback((result: JdbcDriverArtifactResult, fallbackDriverLabel?: string) => {
    Modal.success({
      title: t('dna.drivers.page.importResult.title', '驱动导入完成'),
      content: (
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
      ),
    });
  }, [t]);

  const handleDownload = useCallback(async (_keys: React.Key[], rows: JdbcDriverRow[]) => {
    const driver = rows?.[0];
    if (!driver?.id) {
      message.warning(t('dna.drivers.page.warning.selectDriver', '请选择要下载的驱动'));
      return;
    }

    const hide = message.loading(t('dna.drivers.page.progress.downloading', '正在下载驱动...'), 0);
    try {
      const result = await post<JdbcDriverArtifactResult>(`${baseConfig.baseUrl}/${driver.id}/download`, {});
      hide();
      message.success(t('dna.drivers.page.success.download', '驱动下载成功'));
      showArtifactResult(result, driver.code || driver.name);
      refreshTable();
    } catch (error) {
      hide();
      message.error(resolveErrorMessage(error, t('dna.drivers.page.error.download', '驱动下载失败')));
    }
  }, [refreshTable, showArtifactResult, t]);

  const closeUploadModal = useCallback(() => {
    if (uploading) {
      return;
    }
    setUploadModalOpen(false);
    setUploadTarget(null);
    uploadForm.resetFields();
  }, [uploadForm, uploading]);

  const openUploadModal = useCallback((_keys: React.Key[], rows: JdbcDriverRow[]) => {
    const driver = rows?.[0];
    uploadForm.resetFields();
    if (driver?.id) {
      setUploadMode('replace');
      setUploadTarget(driver);
      uploadForm.setFieldsValue({file: []});
    } else {
      setUploadMode('create');
      setUploadTarget(null);
      uploadForm.setFieldsValue({
        enabled: true,
        file: [],
      });
    }
    setUploadModalOpen(true);
  }, [uploadForm]);

  const handleUpload = useCallback(async () => {
    const values = await uploadForm.validateFields();
    const targetFile = values.file?.[0]?.originFileObj as File | undefined;
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
      formData.append('enabled', String(values.enabled !== false));
      if (values.downloadUrl?.trim()) {
        formData.append('downloadUrl', values.downloadUrl.trim());
      }
      if (values.description?.trim()) {
        formData.append('description', values.description.trim());
      }
    } else {
      if (!uploadTarget?.id) {
        message.warning(t('dna.drivers.page.warning.selectUploadTarget', '请选择要上传的驱动'));
        return;
      }
      url = `${baseConfig.baseUrl}/${uploadTarget.id}/upload`;
    }
    setUploading(true);
    try {
      const result = await request<JdbcDriverArtifactResult>(url, {
        method: 'POST',
        body: formData,
      });
      message.success(uploadMode === 'create'
        ? t('dna.drivers.page.success.uploadCreate', '驱动上传并创建成功')
        : t('dna.drivers.page.success.uploadReplace', '驱动上传成功'));
      closeUploadModal();
      showArtifactResult(result, uploadTarget?.code || uploadTarget?.name || values.code);
      refreshTable();
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.drivers.page.error.upload', '驱动上传失败')));
    } finally {
      setUploading(false);
    }
  }, [baseConfig.baseUrl, closeUploadModal, refreshTable, showArtifactResult, t, uploadForm, uploadMode, uploadTarget]);

  const customButtonEvents: Record<string, (selectedRowKeys: React.Key[], selectedRows: JdbcDriverRow[],
    props: TableButtonProps) => void> = {
    download: (selectedRowKeys, selectedRows) => {
      void handleDownload(selectedRowKeys, selectedRows);
    },
    upload: (selectedRowKeys, selectedRows) => {
      openUploadModal(selectedRowKeys, selectedRows);
    },
  };

  return (
    <>
      <SimpleTable
        key={tableKey}
        {...baseConfig}
        formSchemaTransform={formSchemaTransform}
        columnOverrides={columnOverrides}
        customButtonEvents={customButtonEvents}
      />
      <Modal
        title={uploadMode === 'create'
          ? t('dna.drivers.page.modal.createTitle', '上传并新增驱动')
          : t('dna.drivers.page.modal.replaceTitle', '上传驱动包')}
        open={uploadModalOpen}
        destroyOnHidden
        okText={uploadMode === 'create'
          ? t('dna.drivers.page.modal.createOk', '上传并创建')
          : t('dna.drivers.page.modal.replaceOk', '上传并替换')}
        cancelText={t('cancel', '取消')}
        confirmLoading={uploading}
        onCancel={closeUploadModal}
        onOk={() => void handleUpload()}
      >
        {uploadMode === 'create' ? (
          <Alert
            type="info"
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
            showIcon
            style={{marginBottom: 16}}
            message={t('dna.drivers.page.modal.replaceAlert.title', '替换已有驱动包')}
            description={t(
              'dna.drivers.page.modal.replaceAlert.description',
              '重新上传后会覆盖本地驱动文件，并自动刷新驱动类、版本和 JDBC URL 规则。',
            )}
          />
        )}
        <Form
          form={uploadForm}
          layout="vertical"
          initialValues={{enabled: true}}
        >
          {uploadMode === 'create' ? (
            <>
              <Form.Item
                label={t('dna.drivers.page.form.name.label', '驱动名称')}
                name="name"
                rules={[{required: true, message: t('dna.drivers.page.form.name.required', '请输入驱动名称')}]}
              >
                <Input maxLength={128} placeholder={t('dna.drivers.page.form.name.placeholder', '例如 MySQL')} />
              </Form.Item>
              <Form.Item
                label={t('dna.drivers.page.form.code.label', '驱动编码')}
                name="code"
                rules={[{required: true, message: t('dna.drivers.page.form.code.required', '请输入驱动编码')}]}
              >
                <Input maxLength={128} placeholder={t('dna.drivers.page.form.code.placeholder', '例如 mysql')} />
              </Form.Item>
              <Form.Item
                label={t('dna.drivers.page.form.databaseType.label', '数据库类型')}
                name="databaseType"
                rules={[{required: true, message: t('dna.drivers.page.form.databaseType.required', '请输入数据库类型')}]}
              >
                <Input
                  maxLength={64}
                  placeholder={t('dna.drivers.page.form.databaseType.placeholder', '例如 mysql、postgresql、oracle')}
                />
              </Form.Item>
              <Form.Item label={t('dna.drivers.page.form.downloadUrl.label', '下载地址')} name="downloadUrl">
                <Input
                  maxLength={2048}
                  placeholder={t('dna.drivers.page.form.downloadUrl.placeholder', '可选，便于后续远程重新下载驱动')}
                />
              </Form.Item>
              <Form.Item label={t('dna.drivers.page.form.description.label', '描述')} name="description">
                <TextArea maxLength={512} placeholder={t('dna.drivers.page.form.description.placeholder', '可选备注')} />
              </Form.Item>
              <Form.Item label={t('dna.drivers.page.form.enabled.label', '是否启用')} name="enabled" valuePropName="checked">
                <Switch />
              </Form.Item>
            </>
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
          <Form.Item
            label={t('dna.drivers.page.form.jar.label', '驱动 JAR')}
            name="file"
            valuePropName="fileList"
            getValueFromEvent={normalizeUploadEvent}
            rules={[{required: true, message: t('dna.drivers.page.form.jar.required', '请上传驱动 JAR 文件')}]}
          >
            <Dragger beforeUpload={() => false} multiple={false} maxCount={1} name="file" accept=".jar">
              <p className="ant-upload-drag-icon">
                <InboxOutlined />
              </p>
              <p className="ant-upload-text">{t('dna.drivers.page.form.jar.dragText', '点击或拖拽驱动 JAR 到此处上传')}</p>
              <p className="ant-upload-hint">{t('dna.drivers.page.form.jar.hint', '仅支持单个 JDBC 驱动 JAR 文件')}</p>
            </Dragger>
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
};

export default App;
