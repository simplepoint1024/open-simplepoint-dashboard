import {request} from '@simplepoint/shared/api/client';
import {get, post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';
import type {RuntimeSecret} from './types';
import {formatDateTime, resolveErrorMessage} from './utils';

const {Text} = Typography;

type RuntimeSecretsProps = {
  secretsUrl: string;
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

const RuntimeSecrets = ({secretsUrl}: RuntimeSecretsProps) => {
  const {t} = useI18n();
  const [createForm] = Form.useForm<CreateSecretValues>();
  const [rotateForm] = Form.useForm<RotateSecretValues>();
  const [rows, setRows] = useState<RuntimeSecret[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [rotatingSecret, setRotatingSecret] = useState<RuntimeSecret>();
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const page = await get<Page<RuntimeSecret>>(secretsUrl, {page: 0, size: 500});
      setRows(page.content ?? []);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.runtime.error.secrets.load', 'Runtime Secret 加载失败'),
      ));
    } finally {
      setLoading(false);
    }
  }, [secretsUrl, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const createSecret = useCallback(async () => {
    let values: CreateSecretValues;
    try {
      values = await createForm.validateFields();
    } catch {
      return;
    }
    setSubmitting(true);
    try {
      await post(secretsUrl, values);
      createForm.resetFields();
      setCreateOpen(false);
      message.success(t('ai.runtime.success.secret.created', 'Runtime Secret 已创建'));
      await load();
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.runtime.error.secret.create', 'Runtime Secret 创建失败'),
      ));
    } finally {
      setSubmitting(false);
    }
  }, [createForm, load, secretsUrl, t]);

  const rotateSecret = useCallback(async () => {
    if (!rotatingSecret) return;
    let values: RotateSecretValues;
    try {
      values = await rotateForm.validateFields();
    } catch {
      return;
    }
    setSubmitting(true);
    try {
      await post(`${secretsUrl}/${rotatingSecret.id}/rotate`, values);
      rotateForm.resetFields();
      setRotatingSecret(undefined);
      message.success(t('ai.runtime.success.secret.rotated', 'Runtime Secret 已轮换'));
      await load();
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.runtime.error.secret.rotate', 'Runtime Secret 轮换失败'),
      ));
    } finally {
      setSubmitting(false);
    }
  }, [load, rotateForm, rotatingSecret, secretsUrl, t]);

  const removeSecret = useCallback(async (secret: RuntimeSecret) => {
    try {
      await request<void>(secretsUrl, {
        method: 'DELETE',
        json: [secret.id],
      });
      message.success(t('ai.runtime.success.secret.deleted', 'Runtime Secret 已删除'));
      await load();
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.runtime.error.secret.delete', 'Runtime Secret 删除失败'),
      ));
    }
  }, [load, secretsUrl, t]);

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
    {
      title: t('ai.runtime.field.action', '操作'),
      key: 'action',
      width: 180,
      render: (_: unknown, secret: RuntimeSecret) => (
        <Space>
          <Button type="link" size="small" onClick={() => {
            rotateForm.resetFields();
            setRotatingSecret(secret);
          }}>
            {t('ai.runtime.action.rotate', '轮换')}
          </Button>
          <Button
            type="link"
            danger
            size="small"
            onClick={() => Modal.confirm({
              title: t('ai.runtime.confirm.secret.delete', '确认删除该 Runtime Secret？'),
              content: t(
                'ai.runtime.confirm.secret.deleteDescription',
                '仍被 Pool 或 Workload 引用的 Secret 可能导致后续调度失败。',
              ),
              okButtonProps: {danger: true},
              onOk: () => removeSecret(secret),
            })}
          >
            {t('ai.runtime.action.delete', '删除')}
          </Button>
        </Space>
      ),
    },
  ], [removeSecret, rotateForm, t]);

  return (
    <>
      <Card
        title={t('ai.runtime.secret.title', 'Runtime Secrets')}
        extra={(
          <Space>
            <Button onClick={() => void load()}>
              {t('ai.runtime.action.refresh', '刷新')}
            </Button>
            <Button type="primary" onClick={() => {
              createForm.resetFields();
              createForm.setFieldsValue({enabled: true});
              setCreateOpen(true);
            }}>
              {t('ai.runtime.action.secret.create', '新建 Secret')}
            </Button>
          </Space>
        )}
      >
        <Alert
          showIcon
          type="warning"
          style={{marginBottom: 16}}
          message={t(
            'ai.runtime.secret.notice',
            'Secret 明文只在创建或轮换时提交，之后不会通过查询接口返回。',
          )}
        />
        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={rows}
          pagination={{pageSize: 20, hideOnSinglePage: true}}
        />
      </Card>
      <Modal
        open={createOpen}
        title={t('ai.runtime.secret.create', '创建 Runtime Secret')}
        okText={t('ai.runtime.action.create', '创建')}
        confirmLoading={submitting}
        destroyOnHidden
        onCancel={() => {
          createForm.resetFields();
          setCreateOpen(false);
        }}
        onOk={() => void createSecret()}
      >
        <Form form={createForm} layout="vertical" initialValues={{enabled: true}}>
          <Form.Item
            name="code"
            label={t('ai.runtime.field.code', '编码')}
            rules={[
              {required: true},
              {pattern: /^[a-z0-9][a-z0-9_.-]{0,63}$/},
            ]}
          >
            <Input maxLength={64}/>
          </Form.Item>
          <Form.Item
            name="name"
            label={t('ai.runtime.field.name', '名称')}
            rules={[{required: true, max: 128}]}
          >
            <Input maxLength={128}/>
          </Form.Item>
          <Form.Item
            name="description"
            label={t('ai.runtime.field.description', '说明')}
          >
            <Input.TextArea maxLength={512} autoSize={{minRows: 2, maxRows: 5}}/>
          </Form.Item>
          <Form.Item
            name="value"
            label={t('ai.runtime.field.secretValue', 'Secret 值')}
            rules={[{required: true}]}
          >
            <Input.Password autoComplete="new-password"/>
          </Form.Item>
          <Form.Item
            name="enabled"
            label={t('ai.runtime.field.enabled', '启用')}
            valuePropName="checked"
          >
            <Switch/>
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        open={Boolean(rotatingSecret)}
        title={`${t('ai.runtime.action.rotate', '轮换')} · ${rotatingSecret?.name ?? ''}`}
        okText={t('ai.runtime.action.rotate', '轮换')}
        confirmLoading={submitting}
        destroyOnHidden
        onCancel={() => setRotatingSecret(undefined)}
        onOk={() => void rotateSecret()}
      >
        <Alert
          showIcon
          type="info"
          style={{marginBottom: 16}}
          message={t(
            'ai.runtime.secret.rotateNotice',
            '新值不会回显，正在运行的副本不会自动读取轮换后的内容。',
          )}
        />
        <Form form={rotateForm} layout="vertical">
          <Form.Item
            name="value"
            label={t('ai.runtime.field.secretValue', 'Secret 值')}
            rules={[{required: true}]}
          >
            <Input.Password autoComplete="new-password"/>
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
};

export default RuntimeSecrets;
