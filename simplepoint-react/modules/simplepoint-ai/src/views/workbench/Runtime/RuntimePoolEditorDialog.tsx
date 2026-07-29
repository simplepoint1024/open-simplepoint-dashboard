import {get, post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {
  Alert,
  Col,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  message,
} from 'antd';
import {useEffect, useMemo, useState} from 'react';
import type {
  RuntimePool,
  RuntimePoolFormValues,
  RuntimeSecret,
  RuntimeServer,
} from './types';
import {resolveErrorMessage, splitHosts} from './utils';

type RuntimePoolEditorDialogProps = {
  open: boolean;
  pool?: RuntimePool;
  servers: RuntimeServer[];
  fixedServer?: RuntimeServer;
  poolsUrl: string;
  secretsUrl: string;
  onCancel: () => void;
  onSaved: (pool: RuntimePool) => void;
};

const MIB = 1024 * 1024;
const NANO_CPU = 1_000_000_000;

const RuntimePoolEditorDialog = ({
  open,
  pool,
  servers,
  fixedServer,
  poolsUrl,
  secretsUrl,
  onCancel,
  onSaved,
}: RuntimePoolEditorDialogProps) => {
  const {t} = useI18n();
  const [form] = Form.useForm<RuntimePoolFormValues>();
  const [secrets, setSecrets] = useState<RuntimeSecret[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const networkMode = Form.useWatch('networkMode', form);

  const availableServers = useMemo(() => {
    if (fixedServer) return [fixedServer];
    return servers.filter((server) => (
      server.deploymentType === 'MANAGED_OCI' && server.enabled !== false
    ));
  }, [fixedServer, servers]);

  useEffect(() => {
    if (!open) return;
    void get<Page<RuntimeSecret>>(secretsUrl, {page: 0, size: 500})
      .then((page) => setSecrets(
        (page.content ?? []).filter((secret) => secret.enabled !== false),
      ))
      .catch((error) => message.error(resolveErrorMessage(
        error,
        t('ai.runtime.error.secrets.load', 'Runtime Secret 加载失败'),
      )));
  }, [open, secretsUrl, t]);

  useEffect(() => {
    if (!open) return;
    const defaultCode = `${fixedServer?.code ?? 'mcp'}-pool`.slice(0, 64);
    const defaultName = `${fixedServer?.name ?? 'MCP'} Runtime Pool`.slice(0, 128);
    form.setFieldsValue({
      code: pool?.code ?? defaultCode,
      name: pool?.name ?? defaultName,
      serverId: pool?.serverId ?? fixedServer?.id,
      imageReference: pool?.imageReference ?? '',
      imageDigest: pool?.imageDigest ?? '',
      memoryMiB: pool ? pool.requestedMemoryBytes / MIB : 256,
      cpuCores: pool ? pool.requestedNanoCpus / NANO_CPU : 0.5,
      pidsLimit: pool?.requestedPidsLimit ?? 128,
      networkMode: pool?.networkMode ?? 'none',
      egressAllowlist: (pool?.egressAllowlist ?? []).join('\n'),
      secretIds: pool?.secretIds ?? [],
      minReplicas: pool?.minReplicas ?? 0,
      maxReplicas: pool?.maxReplicas ?? 5,
      desiredReplicas: pool?.desiredReplicas ?? 1,
      activationReplicas: pool?.activationReplicas ?? 1,
      prewarmNodes: pool?.prewarmNodes ?? 1,
      idleTimeoutSeconds: pool?.idleTimeoutSeconds ?? 300,
      replicaLifetimeSeconds: pool?.replicaLifetimeSeconds ?? 3600,
    });
  }, [fixedServer, form, open, pool]);

  const submit = async () => {
    let values: RuntimePoolFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    if (values.minReplicas > values.maxReplicas
        || values.desiredReplicas < values.minReplicas
        || values.desiredReplicas > values.maxReplicas
        || values.activationReplicas > values.maxReplicas
        || values.prewarmNodes > values.maxReplicas
        || (values.idleTimeoutSeconds !== 0 && values.idleTimeoutSeconds < 30)) {
      message.error(t(
        'ai.runtime.error.pool.replicaPolicy',
        '副本或生命周期配置不符合约束，请检查最小、最大、目标和激活副本数。',
      ));
      return;
    }
    setSubmitting(true);
    try {
      const result = await post<RuntimePool>(poolsUrl, {
        code: values.code,
        name: values.name,
        serverId: fixedServer?.id ?? values.serverId,
        imageReference: values.imageReference,
        imageDigest: values.imageDigest.toLowerCase(),
        memoryBytes: Math.round(values.memoryMiB * MIB),
        nanoCpus: Math.round(values.cpuCores * NANO_CPU),
        pidsLimit: values.pidsLimit,
        networkMode: values.networkMode,
        egressAllowlist: values.networkMode === 'egress'
          ? splitHosts(values.egressAllowlist)
          : [],
        secretIds: values.secretIds ?? [],
        minReplicas: values.minReplicas,
        maxReplicas: values.maxReplicas,
        desiredReplicas: values.desiredReplicas,
        activationReplicas: values.activationReplicas,
        prewarmNodes: values.prewarmNodes,
        idleTimeoutSeconds: values.idleTimeoutSeconds,
        replicaLifetimeSeconds: values.replicaLifetimeSeconds,
      });
      message.success(t(
        'ai.runtime.success.pool.saved',
        'Runtime Pool 已保存，调度器正在协调副本',
      ));
      onSaved(result);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.runtime.error.pool.save', 'Runtime Pool 保存失败'),
      ));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title={pool
        ? t('ai.runtime.pool.edit', '编辑 Runtime Pool')
        : t('ai.runtime.pool.create', '部署托管 OCI MCP Server')}
      width={920}
      okText={t('ai.runtime.action.saveDeploy', '保存并部署')}
      confirmLoading={submitting}
      destroyOnHidden
      onCancel={onCancel}
      onOk={() => void submit()}
    >
      <Alert
        showIcon
        type="info"
        style={{marginBottom: 16}}
        message={t(
          'ai.runtime.pool.digestNotice',
          '镜像必须使用独立 sha256 Digest，并包含 stdio MCP 与协议版本标签。',
        )}
      />
      <Form form={form} layout="vertical">
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item
              name="code"
              label={t('ai.runtime.field.code', '编码')}
              rules={[
                {required: true},
                {pattern: /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/},
              ]}
            >
              <Input disabled={Boolean(pool)} maxLength={64}/>
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="name"
              label={t('ai.runtime.field.name', '名称')}
              rules={[{required: true, max: 128}]}
            >
              <Input maxLength={128}/>
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="serverId"
              label={t('ai.runtime.field.server', 'MCP Server')}
              rules={[{required: true}]}
            >
              <Select
                disabled={Boolean(fixedServer)}
                options={availableServers.map((server) => ({
                  value: server.id,
                  label: server.name || server.code || server.id,
                }))}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="networkMode"
              label={t('ai.runtime.field.networkMode', '网络模式')}
              rules={[{required: true}]}
            >
              <Select
                options={[
                  {value: 'none', label: t('ai.runtime.network.none', '禁止网络')},
                  {value: 'bridge', label: t('ai.runtime.network.bridge', 'Bridge 网络')},
                  {value: 'egress', label: t('ai.runtime.network.egress', '受控出口')},
                ]}
              />
            </Form.Item>
          </Col>
          <Col span={24}>
            <Form.Item
              name="imageReference"
              label={t('ai.runtime.field.imageReference', 'OCI 镜像')}
              rules={[{required: true, max: 440}]}
              extra={t(
                'ai.runtime.imageReference.help',
                '例如 docker.io/example/weather-mcp:1.0.0；不要包含 @sha256。',
              )}
            >
              <Input placeholder="docker.io/example/mcp-server:1.0.0"/>
            </Form.Item>
          </Col>
          <Col span={24}>
            <Form.Item
              name="imageDigest"
              label={t('ai.runtime.field.imageDigest', '镜像 Digest')}
              rules={[
                {required: true},
                {pattern: /^sha256:[a-fA-F0-9]{64}$/},
              ]}
            >
              <Input placeholder="sha256:..."/>
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="memoryMiB"
              label={t('ai.runtime.field.memory', '内存上限（MiB）')}
              rules={[{required: true}]}
            >
              <InputNumber min={32} max={16384} precision={0} style={{width: '100%'}}/>
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="cpuCores"
              label={t('ai.runtime.field.cpu', 'CPU 上限（核）')}
              rules={[{required: true}]}
            >
              <InputNumber min={0.01} max={16} step={0.1} style={{width: '100%'}}/>
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="pidsLimit"
              label={t('ai.runtime.field.pids', 'PID 上限')}
              rules={[{required: true}]}
            >
              <InputNumber min={1} max={4096} precision={0} style={{width: '100%'}}/>
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name="minReplicas" label={t('ai.runtime.field.minReplicas', '最小副本')}>
              <InputNumber min={0} max={256} precision={0} style={{width: '100%'}}/>
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name="maxReplicas" label={t('ai.runtime.field.maxReplicas', '最大副本')}>
              <InputNumber min={1} max={256} precision={0} style={{width: '100%'}}/>
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name="desiredReplicas" label={t('ai.runtime.field.desiredReplicas', '目标副本')}>
              <InputNumber min={0} max={256} precision={0} style={{width: '100%'}}/>
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name="activationReplicas" label={t('ai.runtime.field.activationReplicas', '激活副本')}>
              <InputNumber min={1} max={256} precision={0} style={{width: '100%'}}/>
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name="prewarmNodes" label={t('ai.runtime.field.prewarmNodes', '预热节点')}>
              <InputNumber min={0} max={256} precision={0} style={{width: '100%'}}/>
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="idleTimeoutSeconds"
              label={t('ai.runtime.field.idleTimeout', '空闲回收（秒）')}
              extra={t('ai.runtime.idleTimeout.help', '0 表示不按空闲时间回收。')}
            >
              <InputNumber min={0} max={86400} precision={0} style={{width: '100%'}}/>
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="replicaLifetimeSeconds"
              label={t('ai.runtime.field.replicaLifetime', '副本寿命（秒）')}
            >
              <InputNumber min={60} max={86400} precision={0} style={{width: '100%'}}/>
            </Form.Item>
          </Col>
          <Col xs={24} md={16}>
            <Form.Item
              name="secretIds"
              label={t('ai.runtime.field.secrets', 'Runtime Secrets')}
            >
              <Select
                mode="multiple"
                allowClear
                options={secrets.map((secret) => ({
                  value: secret.id,
                  label: `${secret.name} (${secret.code})`,
                }))}
              />
            </Form.Item>
          </Col>
          {networkMode === 'egress' && (
            <Col span={24}>
              <Form.Item
                name="egressAllowlist"
                label={t('ai.runtime.field.egressAllowlist', '出口域名白名单')}
                rules={[{required: true}]}
                extra={t(
                  'ai.runtime.egress.help',
                  '使用换行、空格或逗号分隔域名；支持 *.example.com。',
                )}
              >
                <Input.TextArea autoSize={{minRows: 3, maxRows: 8}}/>
              </Form.Item>
            </Col>
          )}
        </Row>
      </Form>
    </Modal>
  );
};

export default RuntimePoolEditorDialog;
