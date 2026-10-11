import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {Alert, App, Descriptions, Modal, Tag, Typography, Upload} from 'antd';
import type {UploadFile} from 'antd/es/upload/interface';
import {InboxOutlined} from '@simplepoint/shared/types/icon';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {SimpleTableColumnOverride} from '@simplepoint/components/SimpleTable/types';
import LazySForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useActionFeedback} from '@simplepoint/shared/hooks/useActionFeedback';
import {usePageLocation, usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {get, post} from '@simplepoint/shared/api/methods';
import {request, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useQueryClient} from '@tanstack/react-query';
import {providerCanCreate, providerFormSchema, providerPayload, quotaFormSchema, quotaPayload, writable} from './forms';
import api from '@/api';

type StorageRow = {id: string; [key: string]: any};
type Section = 'provider-configs' | 'objects' | 'quotas';
const base = api['platform.object-storage'];
const uploadBase = api['object-storage'].baseUrl;
const {Text} = Typography;
const formatBytes = (value: unknown) => {
  if (value == null) return '—';
  if (typeof value !== 'number' || !Number.isFinite(value)) return String(value);
  if (value === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let amount = value, unit = 0;
  while (amount >= 1024 && unit < units.length - 1) {amount /= 1024; unit++;}
  return `${amount.toFixed(amount >= 10 || unit === 0 ? 0 : 2)} ${units[unit]}`;
};
const dateTime = (value: unknown) => typeof value === 'string' && !Number.isNaN(Date.parse(value))
  ? new Date(value).toLocaleString() : String(value ?? '—');

export default function ObjectStorage() {
  const scope = useQueryScope();
  const route = usePageLocation();
  const section: Section = route.includes('/system/object-storage') ? 'provider-configs'
    : route.includes('/platform/storage-quotas') ? 'quotas' : 'objects';
  return <StorageSession key={JSON.stringify([scope, section])} section={section}/>;
}

function StorageSession({section}: {section: Section}) {
  const {message} = App.useApp();
  const {t} = useI18n();
  const selected = usePageSelected();
  const run = useActionFeedback();
  const client = useQueryClient();
  const endpoint = `${base.baseUrl}/${section}`;
  const policy = useSchema(endpoint, {retry: false});
  const schema = policy.data?.schema;
  const can = (key: string) => !policy.error && !!policy.data?.buttons.some(button => button.key === key);
  const [revision, setRevision] = useState(0);
  const [testing, setTesting] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploadDraft, setUploadDraft] = useState<{directory?: string}>({});
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [uploadError, setUploadError] = useState<string>();
  const form = useRef<SFormHandle>(null);
  const mutationLock = useRef(false);
  const mounted = useRef(true);
  const [detailId, setDetailId] = useState<string>();
  const [detail, setDetail] = useState<StorageRow>();
  const [detailError, setDetailError] = useState<string>();
  const [detailLoading, setDetailLoading] = useState(false);
  const detailRequest = useRef<AbortController | undefined>(undefined);
  usePageRetention(!uploadOpen && !detailId && !testing && !uploading, uploadOpen || testing || uploading);
  useEffect(() => {mounted.current = true; return () => {mounted.current = false; detailRequest.current?.abort();};}, []);

  const visible = useMemo(() => section === 'provider-configs'
    ? ['name', 'code', 'type', 'endpoint', 'bucket', 'region', 'secretConfigured', 'enabled', 'defaultProvider', 'description']
    : section === 'quotas' ? ['tenantName', 'tenantId', 'quotaBytes', 'usedBytes', 'remainingBytes', 'enabled', 'description']
      : ['providerCode', 'originalFileName', 'bucket', 'objectKey', 'contentLength', 'sourceServiceName', 'createdAt'],
  [section]);
  const tableSchema = useCallback((source: any) => {
    const copy = structuredClone(source);
    for (const [field, property] of Object.entries(copy.properties ?? {}) as [string, any][]) {
      property['x-ui'] = {...property['x-ui'], 'x-list-visible': visible.includes(field)};
      if (visible.includes(field)) property['x-order'] = visible.indexOf(field);
    }
    return copy;
  }, [visible]);
  const booleanTag = (value: unknown) => typeof value !== 'boolean' ? String(value ?? '—')
    : <Tag color={value ? 'success' : 'default'}>{value ? t('storage.enabled', '启用') : t('storage.disabled', '停用')}</Tag>;
  const overrides: Record<string, SimpleTableColumnOverride<StorageRow>> = section === 'provider-configs' ? {
    name: {width: 190}, code: {width: 150}, bucket: {width: 160}, region: {width: 130},
    type: {width: 150, render: (value: unknown) => typeof value === 'string'
      ? <Tag>{t(`storage.provider.type.${value}`, value)}</Tag> : '—'},
    endpoint: {render: (value: unknown) => value == null ? '—' : String(value)},
    secretConfigured: {width: 110, render: (value: unknown) => typeof value !== 'boolean' ? String(value ?? '—')
      : <Tag color={value ? 'success' : 'error'}>{value ? t('storage.provider.configured', '已配置')
        : t('storage.provider.notConfigured', '未配置')}</Tag>},
    enabled: {width: 100, render: booleanTag}, defaultProvider: {width: 100, render: (value: unknown) =>
      value === true ? <Tag color="blue">{t('storage.provider.default', '系统默认')}</Tag>
        : value === false ? '—' : String(value ?? '—')},
  } : section === 'quotas' ? {
    tenantName: {width: 200}, tenantId: {width: 170},
    quotaBytes: {width: 140, render: (value: unknown) => value === null ? t('storage.unlimited', '不限额') : formatBytes(value)},
    usedBytes: {width: 140, render: formatBytes},
    remainingBytes: {width: 140, render: (value: unknown, row: StorageRow) =>
      value === null && row.quotaBytes === null ? t('storage.unlimited', '不限额') : formatBytes(value)},
    enabled: {width: 100, render: booleanTag},
  } : {
    providerCode: {width: 150}, originalFileName: {width: 220}, bucket: {width: 160},
    objectKey: {width: 260, render: (value: unknown) => <Text code>{String(value ?? '—')}</Text>},
    contentLength: {width: 130, render: formatBytes}, sourceServiceName: {width: 150},
    createdAt: {width: 180, render: dateTime},
  };

  const testProvider = async (row?: StorageRow) => {
    if (!selected || !can('test') || !row?.id || mutationLock.current) return;
    mutationLock.current = true;
    setTesting(true);
    try {
      await run('storage.test', async () => {
        const result = await post<{success: boolean; message?: string}>(`${endpoint}/${row.id}/test`, {});
        if (!result.success) throw new Error(result.message || t('error.operationFailed', '操作失败，请重试'));
        if (mounted.current) message.success(t('storage.provider.testSuccess', '连接成功，Bucket 可访问'));
      });
    } finally {mutationLock.current = false; if (mounted.current) setTesting(false);}
  };
  const closeDetail = () => {
    detailRequest.current?.abort(); detailRequest.current = undefined;
    setDetailId(undefined); setDetail(undefined); setDetailError(undefined); setDetailLoading(false);
  };
  const viewObject = async (row?: StorageRow) => {
    if (!selected || !can('view') || !row?.id || uploadOpen || mutationLock.current) return;
    detailRequest.current?.abort();
    const controller = new AbortController(); detailRequest.current = controller;
    setDetailId(row.id); setDetail(undefined); setDetailError(undefined); setDetailLoading(true);
    try {
      const result = await get<StorageRow>(`${endpoint}/${encodeURIComponent(row.id)}`, undefined, {signal: controller.signal});
      if (mounted.current && !controller.signal.aborted) setDetail(result);
    } catch (error) {
      if (mounted.current && !controller.signal.aborted) setDetailError(resolveApiErrorMessage(error, t('table.loadFail', '加载失败')));
    } finally {if (mounted.current && !controller.signal.aborted) setDetailLoading(false);}
  };
  const downloadObject = async (row?: StorageRow) => {
    if (!selected || !can('download') || !row?.id || mutationLock.current) return;
    mutationLock.current = true;
    try {
      await run('storage.download', async () => {
        const blob = await request<Blob>(`${endpoint}/${encodeURIComponent(row.id)}/content`, {responseType: 'blob'});
        if (!mounted.current) return;
        const link = document.createElement('a'); const url = URL.createObjectURL(blob);
        link.href = url; link.download = row.originalFileName || row.id;
        link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      });
    } finally {mutationLock.current = false;}
  };
  const upload = async ({formData = {}}: {formData?: {directory?: string}}) => {
    if (!selected || !uploadOpen || !can('upload') || mutationLock.current) return;
    const file = files[0]?.originFileObj as File | undefined;
    if (!file) {setUploadError(t('form.required', '这是必填项')); return;}
    mutationLock.current = true; setUploading(true); setUploadError(undefined);
    try {
      const body = new FormData(); body.append('file', file);
      if (formData.directory?.trim()) body.append('directory', formData.directory.trim());
      const stored = await request<StorageRow>(`${uploadBase}/upload`, {method: 'POST', body});
      if (!stored?.id) throw new Error(t('form.file.invalidResponse', '文件上传响应无效'));
      if (mounted.current) {
        message.success(t('table.addSuccess', '新增成功'));
        setUploadOpen(false); setFiles([]); setUploadDraft({}); setRevision(value => value + 1);
        void client.invalidateQueries({predicate: query => query.queryKey.includes(`${base.baseUrl}/quotas`)});
      }
    } catch (error) {
      if (mounted.current) setUploadError(resolveApiErrorMessage(error, t('form.file.uploadFailed', '文件上传或保存失败，请重试')));
    } finally {mutationLock.current = false; if (mounted.current) setUploading(false);}
  };
  const renderFormSchema = (source: any, editing: StorageRow | null) => section === 'provider-configs'
    ? providerFormSchema(source, editing, t) : section === 'quotas' ? quotaFormSchema(source, editing, t) : source;
  const beforeSubmit = ({formData, currentEditing}: {formData: any; currentEditing: StorageRow | null}) =>
    section === 'provider-configs' ? providerPayload(schema!, formData, currentEditing)
      : quotaPayload(schema!, formData, currentEditing);
  const detailLabels: Record<string, string> = {contentType: t('storage.contentType', '内容类型'),
    eTag: t('storage.eTag', 'ETag'), accessUrl: t('storage.accessUrl', '访问地址')};

  return <>
    <SimpleTable<StorageRow> {...base} baseUrl={endpoint} name={`${base.name}.${section}`} storageKey={endpoint}
      allowTabEviction
      queryContext={String(revision)} schemaTransform={tableSchema} columnOverrides={overrides}
      confirmDiscard drawerWidth={section === 'provider-configs' ? 640 : 480}
      formSchemaTransform={renderFormSchema} beforeSubmit={beforeSubmit}
      errorMessageResolver={error => error instanceof RangeError && error.message === 'QUOTA_RANGE_EXCEEDED'
        ? t('storage.quota.invalidLimit', '配额数值超出安全范围') : undefined}
      initialValues={section === 'provider-configs' ? {type: 'MINIO', region: 'us-east-1',
        pathStyleAccess: true, enabled: true, defaultProvider: false} : {enabled: true}}
      formUiSchema={section === 'quotas' ? {tenantId: {'ui:widget': 'EntitySelect',
        'ui:options': {endpoint: api['platform.tenants'].baseUrl, allowClear: false}}} : undefined}
      formCustomValidate={section === 'provider-configs' ? ((data, errors) => {
        if (data.type !== 'S3' && !data.endpoint?.trim() && (!data.id || writable(schema, 'endpoint'))) {
          (errors.endpoint ?? errors).addError(t('storage.provider.endpointRequired', '当前平台必须配置 Endpoint'));
        }
        return errors;
      }) : undefined}
      isButtonDisabled={button => !selected || testing || uploading || uploadOpen || !!detailId || (button.key === 'add' &&
        (section === 'provider-configs' ? !providerCanCreate(schema) : section === 'quotas' && !writable(schema, 'tenantId')))}
      customButtonEvents={{test: (_keys, rows) => {void testProvider(rows[0]);},
        view: (_keys, rows) => {void viewObject(rows[0]);},
        download: (_keys, rows) => {void downloadObject(rows[0]);},
        upload: () => {if (selected && can('upload') && !mutationLock.current && !uploadOpen && !detailId) {
          setUploadDraft({}); setFiles([]); setUploadError(undefined); setUploadOpen(true);
        }}}}/>
    <Modal wrapClassName="object-storage-upload" open={uploadOpen && selected} title={t('storage.upload', '上传对象')} destroyOnHidden={!uploadOpen} confirmLoading={uploading}
      closable={!uploading} maskClosable={!uploading} keyboard={!uploading}
      onCancel={() => {if (!mutationLock.current) {setUploadOpen(false); setFiles([]); setUploadDraft({});}}}
      onOk={() => form.current?.submit()} okButtonProps={{disabled: !can('upload')}}>
      <Alert type="info" showIcon title={t('storage.upload.defaultProvider',
        '文件将通过统一上传入口写入系统默认 OSS，并自动加入当前租户目录。')}/>
      {uploadError && <Alert type="error" showIcon title={uploadError}/>}
      <LazySForm ref={form} hideSubmit disabled={uploading} schema={{type: 'object', properties: {
        directory: {type: 'string', maxLength: 512, title: t('storage.directory', '目录前缀')}}}}
        formData={uploadDraft} onChange={event => {if (!mutationLock.current) setUploadDraft(event.formData ?? {});}} onSubmit={upload}/>
      {/* Binary file selection is specialized; ordinary metadata still uses the platform form. */}
      <Upload.Dragger beforeUpload={() => false} multiple={false} maxCount={1} fileList={files}
        disabled={uploading || !selected} onChange={event => {if (!mutationLock.current && selected) setFiles(event.fileList);}}>
        <p className="ant-upload-drag-icon"><InboxOutlined/></p>
        <p className="ant-upload-text">{t('storage.upload.drag', '点击或拖拽文件到此区域上传')}</p>
      </Upload.Dragger>
    </Modal>
    <Modal open={!!detailId && selected} title={t('storage.detail', '对象详情')} destroyOnHidden={!detailId} footer={null} onCancel={closeDetail}>
      {detailLoading ? <Text>{t('loading.resources', '资源加载中，请稍候...')}</Text>
        : detailError ? <Alert type="error" showIcon title={detailError}/>
          : detail && <Descriptions column={1} bordered size="small">
            {visible.filter(field => field in detail).map(field => <Descriptions.Item key={field}
              label={typeof schema?.properties?.[field] === 'object' ? schema.properties[field].title || field : field}>
              {field === 'contentLength' ? formatBytes(detail[field]) : field === 'createdAt'
                ? dateTime(detail[field]) : String(detail[field] ?? '—')}
            </Descriptions.Item>)}
            {['contentType', 'eTag', 'accessUrl'].filter(field => field in detail).map(field =>
              <Descriptions.Item key={field} label={detailLabels[field]}>{String(detail[field] ?? '—')}</Descriptions.Item>)}
          </Descriptions>}
    </Modal>
  </>;
}
