import DataTable from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import {useRemoteList} from '@simplepoint/components/DataTable/useRemoteList';
import {request} from '@simplepoint/shared/api/client';
import {post} from '@simplepoint/shared/api/methods';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {useOperationScope} from './useOperationScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {
  Alert,
  App,
  Modal,
  Space,
  Tag,
  Typography,
} from 'antd';
import {useCallback, useMemo, useRef, useState} from 'react';
import type {RuntimeSecret} from './types';
import {formatDateTime, resolveErrorMessage} from './utils';

const {Text} = Typography;

type RuntimeSecretsProps = {
  active?: boolean;
  secretsUrl: string;
  canManage: boolean;
};

type CreateSecretValues = {
  code: string;
  name: string;
  description?: string;
  value: string;
  enabled: boolean;
};

type RotateSecretValues = {
  value: string;
};

const RuntimeSecrets = ({active = true, secretsUrl, canManage}: RuntimeSecretsProps) => {
  const {t} = useI18n();
  const {message} = App.useApp();
  const operation = useOperationScope();
  const createForm = useRef<SFormHandle>(null);
  const rotateForm = useRef<SFormHandle>(null);
  const [createValues, setCreateValues] = useState<Partial<CreateSecretValues>>({enabled: true});
  const [rotateValues, setRotateValues] = useState<Partial<RotateSecretValues>>({});
  const submittingRef = useRef(false);
  const list = useRemoteList<RuntimeSecret>(secretsUrl, active);
  const load = list.refresh;
  const loadError = list.error ? resolveErrorMessage(list.error, t('table.loadFail', '加载失败')) : undefined;
  const [createOpen, setCreateOpen] = useState(false);
  const [rotatingSecret, setRotatingSecret] = useState<RuntimeSecret>();
  const [submitting, setSubmitting] = useState(false);
  const [deleting, setDeleting] = useState<RuntimeSecret>();
  const [writeError, setWriteError] = useState('');
  const locked = operation.busy || createOpen || !!rotatingSecret || !!deleting;
  usePageRetention(false, locked);


  const createSecret = useCallback(async (values: CreateSecretValues) => {
    if (!canManage || !createOpen || loadError || submittingRef.current) return;
    const request = operation.begin(); if (!request) return; setWriteError('');
    submittingRef.current = true;
    setSubmitting(true);
    try {
      await post(secretsUrl, values, {signal: request.signal});
      if (!operation.current(request.signal)) return;
      setCreateValues({enabled: true});
      setCreateOpen(false);
      message.success(t('ai.runtime.success.secret.created', 'Runtime Secret 已创建'));
      await load();
    } catch (error) {
      if (!operation.current(request.signal)) return;
      setCreateValues(current => ({...current, value: ''}));
      setWriteError(resolveErrorMessage(
        error,
        t('ai.runtime.error.secret.create', 'Runtime Secret 创建失败'),
      ));
    } finally {
      submittingRef.current = false; operation.finish(request); if (operation.current()) setSubmitting(false);
    }
  }, [canManage, createOpen, loadError, load, message, secretsUrl, t, operation.begin, operation.current, operation.finish]);

  const rotateSecret = useCallback(async (values: RotateSecretValues) => {
    if (!rotatingSecret?.id || !canManage || loadError || submittingRef.current) return;
    const request = operation.begin(); if (!request) return; setWriteError('');
    submittingRef.current = true;
    setSubmitting(true);
    try {
      await post(secretsUrl + '/' + encodeURIComponent(rotatingSecret.id) + '/rotate', values, {signal: request.signal});
      if (!operation.current(request.signal)) return;
      setRotateValues({});
      setRotatingSecret(undefined);
      message.success(t('ai.runtime.success.secret.rotated', 'Runtime Secret 已轮换'));
      await load();
    } catch (error) {
      if (!operation.current(request.signal)) return;
      setRotateValues({});
      setWriteError(resolveErrorMessage(
        error,
        t('ai.runtime.error.secret.rotate', 'Runtime Secret 轮换失败'),
      ));
    } finally {
      submittingRef.current = false; operation.finish(request); if (operation.current()) setSubmitting(false);
    }
  }, [canManage, loadError, load, message, rotatingSecret, secretsUrl, t, operation.begin, operation.current, operation.finish]);

  const removeSecret = async (secret: RuntimeSecret) => {
    if (!canManage || !secret.id || loadError) return;
    const request = operation.begin(); if (!request) return; setWriteError('');
    try {
      await requestDelete(secret.id, request.signal);
      if (!operation.current(request.signal)) return;
      setDeleting(undefined); message.success(t('ai.runtime.success.secret.deleted', 'Runtime Secret 已删除')); await load();
    } catch (error) {if (operation.current(request.signal)) setWriteError(resolveErrorMessage(error,
      t('ai.runtime.error.secret.delete', 'Runtime Secret 删除失败')));}
    finally {operation.finish(request);}
  };
  const requestDelete = (id: string, signal: AbortSignal) => request<void>(secretsUrl, {method: 'DELETE', json: [id], signal});

  const columns = useMemo(() => [
    {
      title: t('ai.runtime.field.name', '名称'),
      dataIndex: 'name',
      width: 220,
      render: (value: string, secret: RuntimeSecret) => (
        <Space direction="vertical" size={0}>
          <Text strong>{value}</Text>
          <Text code type="secondary">{secret.code}</Text>
        </Space>
      ),
    },
    {
      title: t('ai.runtime.field.description', '说明'),
      dataIndex: 'description',
      ellipsis: true,
    },
    {
      title: t('ai.runtime.field.secretValue', '凭证'),
      dataIndex: 'hasValue',
      width: 110,
      render: (value: boolean) => (
        <Tag color={value ? 'green' : 'red'}>
          {value
            ? t('ai.runtime.secret.configured', '已配置')
            : t('ai.runtime.secret.missing', '未配置')}
        </Tag>
      ),
    },
    {
      title: t('ai.runtime.field.enabled', '状态'),
      dataIndex: 'enabled',
      width: 100,
      render: (value: boolean) => (
        <Tag color={value ? 'green' : 'default'}>
          {value
            ? t('ai.runtime.common.enabled', '启用')
            : t('ai.runtime.common.disabled', '禁用')}
        </Tag>
      ),
    },
    {
      title: t('ai.runtime.field.rotatedAt', '最后轮换'),
      dataIndex: 'rotatedAt',
      width: 180,
      render: formatDateTime,
    },
    ...(canManage ? [{
      title: t('ai.runtime.field.action', '操作'),
      key: 'action',
      width: 180,
      render: (_: unknown, secret: RuntimeSecret) => (
        <TableActions selectedKeys={[secret.id]} selectedRows={[secret]} buttons={[
          {key: 'rotate', sort: 1, text: t('ai.runtime.action.rotate', '轮换'), type: 'link', size: 'small'},
          {key: 'delete', sort: 2, text: t('ai.runtime.action.delete', '删除'), type: 'link', size: 'small', danger: true},
        ]} isButtonDisabled={() => locked || !operation.selected || !!loadError}
          onButtonEvents={{rotate: () => {
            if (locked || !operation.isSelected() || !secret.id) return;
            setWriteError(''); setRotateValues({}); setRotatingSecret(structuredClone(secret));
          }, delete: () => {
            if (locked || !operation.isSelected() || !secret.id) return;
            setWriteError(''); setDeleting(structuredClone(secret));
          }}}/>
      ),
    }] : []),
  ], [canManage, t, locked, operation.selected, loadError]);

  return (
    <>
      <div className="runtime-list-secrets" style={{display: 'flex', flexDirection: 'column', minHeight: 0, gap: 12}}>
        {writeError && !createOpen && !rotatingSecret && !deleting && <Alert type='error' showIcon title={writeError}/>}
        {loadError && (
          <Alert
            showIcon
            type="error"
            style={{marginBottom: 16}}
            message={t('ai.runtime.error.secrets.load', 'Runtime Secret 加载失败')}
            description={loadError}
            action={(
              <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
                {key: 'retry', sort: 1, text: t('table.retry', '重试'), size: 'small'},
                {key: 'reset', sort: 2, text: t('table.query.reset', '重置筛选与排序'), size: 'small'},
              ]} onButtonEvents={{retry: list.retry, reset: list.reset}}/>
            )}
          />
        )}
        <Alert
          showIcon
          type="warning"
          style={{marginBottom: 16}}
          message={t(
            'ai.runtime.secret.notice',
            'Secret 明文只在创建或轮换时提交，之后不会通过查询接口返回。',
          )}
        />
        <DataTable<RuntimeSecret>
          {...list.tableProps}
          refresh={() => {if (!locked) void load();}}
          pagination={list.tableProps.pagination && typeof list.tableProps.pagination === 'object' ? {...list.tableProps.pagination, disabled: locked} : list.tableProps.pagination}
          toolbarExtra={<Space>
            {canManage && (
              <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
                {key: 'create', sort: 1, text: t('ai.runtime.action.secret.create', '新建 Secret'), type: 'primary', disabled: locked || !operation.selected || !!loadError},
              ]} onButtonEvents={{create: () => {
                if (locked || !operation.isSelected() || loadError) return;
                setWriteError(''); setCreateValues({enabled: true});
                setCreateOpen(true);
              }}}/>
            )}
          </Space>}
          query={list.tableProps.query}
          columns={list.columns(columns)}
        />
      </div>
      <Modal
        wrapClassName='runtime-secret-create'
        open={createOpen && operation.selected}
        title={t('ai.runtime.secret.create', '创建 Runtime Secret')}
        okText={t('ai.runtime.action.create', '创建')}
        confirmLoading={submitting}
        closable={!submitting} maskClosable={!submitting} keyboard={!submitting}
        destroyOnHidden={!createOpen}
        onCancel={() => {
          if (submittingRef.current) return;
          setCreateValues({enabled: true});
          setCreateOpen(false);
        }}
        onOk={() => createForm.current?.submit()}
      >
        {writeError && <Alert type='error' showIcon title={writeError}/>}
        <SForm ref={createForm} hideSubmit disabled={submitting} schema={{type: 'object', required: ['code', 'name', 'value'], properties: {
          code: {type: 'string', pattern: '^[a-z0-9][a-z0-9_.-]{0,63}$', title: t('ai.runtime.field.code', '编码')},
          name: {type: 'string', minLength: 1, maxLength: 128, pattern: '\\S', title: t('ai.runtime.field.name', '名称')},
          description: {type: 'string', maxLength: 512, title: t('ai.runtime.field.description', '说明')},
          value: {type: 'string', minLength: 1, title: t('ai.runtime.field.secretValue', 'Secret 值')},
          enabled: {type: 'boolean', title: t('ai.runtime.field.enabled', '启用')},
        }}} uiSchema={{description: {'ui:widget': 'textarea'}, value: {'ui:widget': 'password', 'ui:options': {autocomplete: 'new-password'}}}}
          formData={createValues} onChange={({formData}) => {if (!submittingRef.current) setCreateValues(formData ?? {});}}
          onSubmit={({formData}) => void createSecret(formData as CreateSecretValues)}/>
      </Modal>
      <Modal
        wrapClassName='runtime-secret-rotate'
        open={Boolean(rotatingSecret) && operation.selected}
        title={`${t('ai.runtime.action.rotate', '轮换')} · ${rotatingSecret?.name ?? ''}`}
        okText={t('ai.runtime.action.rotate', '轮换')}
        confirmLoading={submitting}
        closable={!submitting} maskClosable={!submitting} keyboard={!submitting}
        destroyOnHidden={!rotatingSecret}
        onCancel={() => {if (!submittingRef.current) {setRotatingSecret(undefined); setRotateValues({});}}}
        onOk={() => rotateForm.current?.submit()}
      >
        {writeError && <Alert type='error' showIcon title={writeError}/>}
        <Alert
          showIcon
          type="info"
          closable
          style={{marginBottom: 16}}
          message={t(
            'ai.runtime.secret.rotateNotice',
            '新值不会回显，正在运行的副本不会自动读取轮换后的内容。',
          )}
        />
        <SForm ref={rotateForm} hideSubmit disabled={submitting} schema={{type: 'object', required: ['value'], properties: {
          value: {type: 'string', minLength: 1, title: t('ai.runtime.field.secretValue', 'Secret 值')},
        }}} uiSchema={{value: {'ui:widget': 'password', 'ui:options': {autocomplete: 'new-password'}}}}
          formData={rotateValues} onChange={({formData}) => {if (!submittingRef.current) setRotateValues(formData ?? {});}}
          onSubmit={({formData}) => void rotateSecret(formData as RotateSecretValues)}/>
      </Modal>
      <Modal wrapClassName="runtime-secret-delete" open={!!deleting && operation.selected} destroyOnHidden={!deleting}
        title={t('ai.runtime.confirm.secret.delete', '确认删除该 Runtime Secret？')}
        closable={!operation.busy} maskClosable={!operation.busy} keyboard={!operation.busy}
        onCancel={() => {if (!operation.busy) setDeleting(undefined);}}
        footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'cancel', sort: 1, text: t('ai.mcp.action.cancel', '取消'), disabled: operation.busy},
          {key: 'confirm', sort: 2, text: t('ai.runtime.action.confirm', '确认'), type: 'primary', danger: true, loading: operation.busy, disabled: operation.busy},
        ]} onButtonEvents={{cancel: () => {if (!operation.busy) setDeleting(undefined);}, confirm: () => {if (deleting) void removeSecret(deleting);}}}/>}>
        <Text strong>{deleting?.name || deleting?.code || '—'}</Text>
        <Typography.Paragraph>{t('ai.runtime.confirm.secret.deleteDescription', '仍被 Pool 或 Workload 引用的 Secret 可能导致后续调度失败。')}</Typography.Paragraph>
        {writeError && <Alert type="error" showIcon title={writeError}/>}
      </Modal>
    </>
  );
};

export default RuntimeSecrets;
