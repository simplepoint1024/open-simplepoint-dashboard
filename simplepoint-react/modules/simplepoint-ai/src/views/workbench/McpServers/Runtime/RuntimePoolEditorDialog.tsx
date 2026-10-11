import {post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import {useDeploymentSchema} from './useDeploymentSchema';
import {useOperationScope} from './useOperationScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {
  Alert,
  App,
  Modal,
} from 'antd';
import {useEffect, useRef, useState} from 'react';
import type {
  RuntimePool,
  RuntimePoolFormValues,
  RuntimeServer,
} from './types';
import {resolveErrorMessage, splitHosts} from './utils';
import {canEditPool} from './rowPolicy';

type RuntimePoolEditorDialogProps = {
  open: boolean;
  pool?: RuntimePool;
  serversUrl: string;
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
  serversUrl,
  fixedServer,
  poolsUrl,
  secretsUrl,
  onCancel,
  onSaved,
}: RuntimePoolEditorDialogProps) => {
  const {t} = useI18n();
  const {message} = App.useApp();
  const operation = useOperationScope();
  const [submitError, setSubmitError] = useState('');
  usePageRetention(false, open);
  const form = useRef<SFormHandle>(null);
  const [formData, setFormData] = useState<Partial<RuntimePoolFormValues>>({});
  const [submitting, setSubmitting] = useState(false);
  const submittingRef = useRef(false);
  const schema = useDeploymentSchema({serversUrl, secretsUrl, fixedServer, pool: true, networkMode: formData.networkMode});


  useEffect(() => {
    if (!open) return;
    setSubmitError('');
    const defaultCode = `${fixedServer?.code ?? 'mcp'}-pool`.slice(0, 64);
    const defaultName = `${fixedServer?.name ?? 'MCP'} Runtime Pool`.slice(0, 128);
    setFormData({
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
  }, [fixedServer, open, pool]);

  const submit = async (values: RuntimePoolFormValues) => {
    if (!open || submittingRef.current || pool && !canEditPool(pool)) return;
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
    const request = operation.begin(); if (!request) return;
    submittingRef.current = true; setSubmitting(true); setSubmitError('');
    try {
      const result = await post<RuntimePool>(poolsUrl, {
        code: values.code,
        name: values.name,
        serverId: fixedServer?.id ?? values.serverId,
        imageReference: values.imageReference,
        imageDigest: values.imageDigest?.trim().toLowerCase() || null,
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
      }, {signal: request.signal});
      if (!operation.current(request.signal)) return;
      message.success(t(
        'ai.runtime.success.pool.saved',
        'Runtime Pool 已保存，调度器正在协调副本',
      ));
      onSaved(result);
    } catch (error) {
      if (!operation.current(request.signal)) return;
      setSubmitError(resolveErrorMessage(
        error,
        t('ai.runtime.error.pool.save', 'Runtime Pool 保存失败'),
      ));
    } finally {
      submittingRef.current = false; operation.finish(request); if (operation.current()) setSubmitting(false);
    }
  };

  return (
    <Modal
      wrapClassName="mcp-pool-editor"
      open={open && operation.selected}
      title={pool
        ? t('ai.runtime.pool.edit', '编辑 Runtime Pool')
        : t('ai.runtime.pool.create', '部署托管 OCI MCP Server')}
      width={920}
      okText={t('ai.runtime.action.saveDeploy', '保存并部署')}
      confirmLoading={submitting}
      closable={!submitting} maskClosable={!submitting} keyboard={!submitting}
      okButtonProps={{disabled: !!pool && !canEditPool(pool)}}
      destroyOnHidden={!open}
      onCancel={() => {if (!submittingRef.current) onCancel();}}
      onOk={() => form.current?.submit()}
    >
      {submitError && <Alert type="error" showIcon title={submitError} style={{marginBottom: 12}}/>}
      <Alert
        showIcon
        type="info"
        closable
        style={{marginBottom: 16}}
        message={t(
          'ai.runtime.pool.digestNotice',
          '镜像必须使用独立 sha256 Digest，并包含 stdio MCP 与协议版本标签。',
        )}
      />
<SForm ref={form} hideSubmit schema={schema} formData={formData} disabled={submitting || !!pool && !canEditPool(pool)}
        uiSchema={{code: {'ui:readonly': Boolean(pool)}, ...(fixedServer ? {serverId: {'ui:readonly': true}} : {})}}
        onChange={({formData}) => {if (!submittingRef.current) setFormData(formData ?? {});}}
        onSubmit={({formData}) => void submit(formData as RuntimePoolFormValues)}/>
    </Modal>
  );
};

export default RuntimePoolEditorDialog;
