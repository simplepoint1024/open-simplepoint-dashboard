import ModelDefinition from './ModelDefinition';
import StatusTag from '@simplepoint/components/StatusTag';
import {runtimeStatus} from './status';
import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {SimpleTablePageRequest} from '@simplepoint/components/SimpleTable/types';
import DataTable from '@simplepoint/components/DataTable';
import {get, post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {Alert, App, Descriptions, Drawer, Modal, Tabs} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useOwnedReads} from '@simplepoint/shared/hooks/useOwnedReads';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import './style.css';
import {useEffect, useMemo, useState} from 'react';
import {formatDateTime, resolveErrorMessage} from '../shared';

const config = api['platform.dna-runtime'];
const endpoint = config.baseUrl;

type Model = {
  id: string; displayName: string; appId: string; formId: string; status: string;
  latestVersion: number; activeVersion?: number; lockVersion: number; updatedAt: string;
};
type ModelDetail = {model: Model; createdAt?: string; schemaJson?: string};
type Version = {
  id: string; physicalModelId: string; versionNumber: number; contractVersion: string;
  schemaChecksum: string; createdAt: string;
};
type Migration = {
  id: string; physicalModelId: string; fromVersion?: number; targetVersion: number;
  status: string; risk?: string; dialectId?: string; errorCode?: string;
  expectedModelVersion: number; startedAt: string; finishedAt?: string;
};
type Audit = {
  id: string; eventType: string; occurredAt: string; actorId: string;
  physicalModelId: string; dataSourceId: string; affectedRows?: number;
  durationMillis: number; outcome: string; errorCode?: string;
};
type Step = {
  sequence: number; operation: string; logicalObject: string; risk: string;
  status?: string; attempts?: number; errorCode?: string;
};
type Plan = {
  physicalModelId: string; targetVersion: number; dialectId: string;
  actualSchemaChecksum: string; targetSchemaChecksum: string; risk: string; steps: Step[];
};
type Publication = {
  migrationId: string; physicalModelId: string; fromVersion?: number; targetVersion: number;
  status: string; risk?: string; dialectId?: string; errorCode?: string; steps: Step[];
};

const pageLoader = <T,>(path: string) =>
  async ({page, size, filters, sort, signal}: SimpleTablePageRequest) => normalizePage(
    await get<RawPage<T>>(endpoint + path, {
      ...filters, page, size, ...(sort ? {sort} : {}),
    }, {signal, notifyOnError: false}),
  );

const recoverableStatuses = new Set(['VALIDATING', 'READY', 'MIGRATING', 'VERIFYING']);

const stepColumns = (t: (key: string, fallback?: string) => string) => [
  {title: t('dna.runtime.column.sequence', '序号'), dataIndex: 'sequence', width: 80},
  {title: t('dna.runtime.column.operation', '操作'), dataIndex: 'operation', width: 180},
  {title: t('dna.runtime.column.logicalObject', '逻辑对象'), dataIndex: 'logicalObject', width: 220},
  {title: t('dna.runtime.column.risk', '风险'), dataIndex: 'risk', width: 130,
    render: (value: string) => <StatusTag {...runtimeStatus(value, t)}/>},
  {title: t('dna.runtime.column.status', '状态'), dataIndex: 'status', width: 130,
    render: (value?: string) => value ? <StatusTag {...runtimeStatus(value, t)}/> : '—'},
  {title: t('dna.runtime.column.attempts', '尝试次数'), dataIndex: 'attempts', width: 110},
  {title: t('dna.runtime.column.errorCode', '错误码'), dataIndex: 'errorCode', width: 260,
    render: (value?: string) => value || '—'},
];

export default function RuntimeGovernance() {
  const {t, ensure, locale} = useI18n();
  const {message} = App.useApp();
  const [revision, setRevision] = useState(0);
  const [modelDetail, setModelDetail] = useState<ModelDetail>();
  const [modelId, setModelId] = useState<string>();
  const detailScope = useOperationScope('dna.runtime');
  const reads = useOwnedReads(detailScope, true);
  const [confirmation, setConfirmation] = useState<{migration: Migration; action: 'retry' | 'recover'}>();
  const [plan, setPlan] = useState<Plan>();
  const [publication, setPublication] = useState<Publication>();
  const detailLoading = reads.loading.length > 0;
  const modelError = reads.errors.model ? resolveErrorMessage(reads.errors.model, t('dna.runtime.model.detailFailed', '模型详情加载失败')) : '';
  usePageRetention(!modelId && !plan && !publication && !confirmation && !detailScope.busy,
    !!confirmation || detailScope.busy);
  useEffect(() => {setModelId(undefined); setModelDetail(undefined); setPlan(undefined); setPublication(undefined);
    setConfirmation(undefined); reads.clearErrors();}, [detailScope.current]);

  useEffect(() => {if (!detailScope.selected && !modelDetail) setModelId(undefined);}, [detailScope.selected, modelDetail]);
  useEffect(() => { void ensure(config.i18nNamespaces); }, [ensure, locale]);

  const loadModels = useMemo(() => pageLoader<Model>('/models'), []);
  const loadVersions = useMemo(() => pageLoader<Version>('/versions'), []);
  const loadMigrations = useMemo(() => pageLoader<Migration>('/migrations'), []);
  const loadAudits = useMemo(() => pageLoader<Audit>('/audits'), []);

  const openModel = (model: Model) => {
    reads.cancel('model'); reads.clearErrors('model'); setModelId(model.id); setModelDetail(undefined);
    void reads.run('model', signal => get<ModelDetail>(endpoint + '/models/' + encodeURIComponent(model.id),
      undefined, {signal, notifyOnError: false}), setModelDetail);
  };
  const openPlan = (model: Model) => {
    reads.cancel('plan'); reads.clearErrors('plan'); setPlan(undefined);
    void reads.run('plan', signal => get<Plan>(endpoint + '/models/' + encodeURIComponent(model.id) + '/plan',
      {targetVersion: model.latestVersion}, {signal, notifyOnError: false}), setPlan);
  };
  const openMigration = (migration: Migration) => {
    reads.cancel('migration'); reads.clearErrors('migration'); setPublication(undefined);
    void reads.run('migration', signal => get<Publication>(endpoint + '/migrations/' + migration.id,
      undefined, {signal, notifyOnError: false}), setPublication);
  };
  const retryMigration = (migration: Migration) => {
    if (migration.status === 'FAILED') setConfirmation({migration, action: 'retry'});
  };
  const recoverMigration = (migration: Migration) => {
    if (recoverableStatuses.has(migration.status)) setConfirmation({migration, action: 'recover'});
  };
  const confirmMigration = async () => {
    if (!confirmation) return;
    const operation = detailScope.begin(); if (!operation) return;
    const {migration, action} = confirmation;
    try {
      await post(endpoint + '/migrations/' + migration.id + '/' + action, undefined, {
        params: {expectedVersion: migration.expectedModelVersion}, signal: operation.signal, notifyOnError: false,
      });
      if (detailScope.current(operation.signal)) {
        setConfirmation(undefined); setRevision(value => value + 1);
        message.success(action === 'retry' ? t('dna.runtime.retryStarted', '迁移重试已完成')
          : t('dna.runtime.recoverCompleted', '迁移已隔离并转为失败状态'));
      }
    } catch (error) {
      if (detailScope.current(operation.signal)) message.error(resolveErrorMessage(error, action === 'retry'
        ? t('dna.runtime.retryFailed', '迁移重试失败') : t('dna.runtime.recoverFailed', '迁移恢复失败')));
    } finally {detailScope.finish(operation);}
  };

  const commonDateColumns = useMemo(() => ({
    updatedAt: {width: 180, render: (value?: string) => formatDateTime(value)},
    createdAt: {width: 180, render: (value?: string) => formatDateTime(value)},
    startedAt: {width: 180, render: (value?: string) => formatDateTime(value)},
    finishedAt: {width: 180, render: (value?: string) => formatDateTime(value)},
    occurredAt: {width: 180, render: (value?: string) => formatDateTime(value)},
  }), []);
  const statusColumns = useMemo(() => ({
    status: {width: 130, render: (value: string) => <StatusTag {...runtimeStatus(value, t)}/>},
    risk: {width: 130, render: (value?: string) => value
      ? <StatusTag {...runtimeStatus(value, t)}/> : '—'},
    outcome: {width: 130, render: (value: string) => <StatusTag {...runtimeStatus(value, t)}/>},
  }), [t]);
  const detailColumns = useMemo(() => stepColumns(t), [t]);

  return <>
    {['plan', 'migration'].map(channel => !!reads.errors[channel] && <Alert key={channel} type="error" showIcon
      title={resolveErrorMessage(reads.errors[channel], channel === 'plan' ? t('dna.runtime.loadPlanFailed', '迁移计划加载失败')
        : t('dna.runtime.loadMigrationFailed', '迁移详情加载失败'))}/>)}
    <Modal open={!!confirmation && detailScope.selected} confirmLoading={detailScope.busy}
      title={confirmation?.action === 'retry' ? t('dna.runtime.confirmRetry', '确认重试这个迁移？')
        : t('dna.runtime.confirmRecover', '确认隔离并终止这个无活动迁移？')}
      okButtonProps={{danger: confirmation?.action === 'recover'}} closable={!detailScope.busy}
      maskClosable={!detailScope.busy} keyboard={!detailScope.busy} onOk={() => void confirmMigration()}
      onCancel={() => {if (!detailScope.isBusy()) setConfirmation(undefined);}}>
      {confirmation?.action === 'recover' && t('dna.runtime.recoverHint', '达到服务端无活动阈值后，迁移会转为失败；请检查目标表后再执行重试。')}
    </Modal>
    <Tabs className="dna-runtime-tabs" items={[
      {key: 'models', label: t('dna.runtime.models.title', '物理模型'), children:
        <SimpleTable<Model> baseUrl={endpoint + '/models'} name="dna-runtime-models"
          i18nNamespaces={config.i18nNamespaces} loadPage={loadModels}
          queryContext={String(revision)} columnOverrides={{...commonDateColumns, ...statusColumns}}
          isButtonDisabled={() => detailScope.busy || detailLoading || !!confirmation}
          customButtonEvents={{view: (_keys, rows) => {if (rows[0]) void openModel(rows[0]);},
            plan: (_keys, rows) => {if (rows[0]) void openPlan(rows[0]);}}}/>
      },
      {key: 'versions', label: t('dna.runtime.versions.title', '模型版本'), children:
        <SimpleTable<Version> baseUrl={endpoint + '/versions'} name="dna-runtime-versions"
          i18nNamespaces={config.i18nNamespaces} loadPage={loadVersions}
          queryContext={String(revision)} columnOverrides={commonDateColumns}/>
      },
      {key: 'migrations', label: t('dna.runtime.migrations.title', '结构迁移'), children:
        <SimpleTable<Migration> baseUrl={endpoint + '/migrations'} name="dna-runtime-migrations"
          i18nNamespaces={config.i18nNamespaces} loadPage={loadMigrations}
          queryContext={String(revision)} columnOverrides={{...commonDateColumns, ...statusColumns}}
          customButtonEvents={{
            view: (_keys, rows) => {if (rows[0]) void openMigration(rows[0]);},
            recover: (_keys, rows) => {if (rows[0]) recoverMigration(rows[0]);},
            retry: (_keys, rows) => {if (rows[0]) retryMigration(rows[0]);},
          }}/>
      },
      {key: 'audits', label: t('dna.runtime.audits.title', '运行审计'), children:
        <SimpleTable<Audit> baseUrl={endpoint + '/audits'} name="dna-runtime-audits"
          i18nNamespaces={config.i18nNamespaces} loadPage={loadAudits}
          queryContext={String(revision)} columnOverrides={{...commonDateColumns, ...statusColumns}}/>
      },
    ]}/>

    <Drawer rootClassName="sp-simple-table-drawer" title={t('dna.runtime.model.detail', '物理模型详情')}
      width="min(800px, 94vw)" open={!!modelId && detailScope.selected} loading={detailLoading} destroyOnHidden
      onClose={() => {setModelId(undefined); setModelDetail(undefined); reads.cancel('model'); reads.clearErrors('model');}}>
      {modelError && <Alert type="error" showIcon title={modelError}
        action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0,
          text: t('dna.runtime.model.retry', '重试')}]}
          onButtonEvents={{retry: () => {if (modelId) void openModel({id: modelId} as Model);}}}/>}/>}
      {modelDetail && <>
        <Descriptions column={2} size="small" items={[
          {key: 'name', label: t('dna.runtime.column.displayName', '名称'), children: modelDetail.model.displayName},
          {key: 'status', label: t('dna.runtime.column.status', '状态'), children: <StatusTag {...runtimeStatus(modelDetail.model.status, t)}/>},
          {key: 'model', label: t('dna.runtime.column.physicalModelId', '模型 ID'), children: modelDetail.model.id},
          {key: 'app', label: t('dna.runtime.column.appId', '应用 ID'), children: modelDetail.model.appId},
          {key: 'form', label: t('dna.runtime.column.formId', '表单 ID'), children: modelDetail.model.formId},
          {key: 'latest', label: t('dna.runtime.column.latestVersion', '最新版本'), children: modelDetail.model.latestVersion},
          {key: 'active', label: t('dna.runtime.column.activeVersion', '活动版本'), children: modelDetail.model.activeVersion ?? '—'},
          {key: 'updated', label: t('dna.runtime.column.updatedAt', '更新时间'), children: formatDateTime(modelDetail.model.updatedAt)},
        ]}/>
        <div style={{marginTop: 16, marginBottom: 8}}>{t('dna.runtime.model.schema', '最新模型定义')}</div>
        <ModelDefinition source={modelDetail.schemaJson}/>
      </>}
    </Drawer>

    <Drawer title={t('dna.runtime.plan.title', '结构差异与迁移计划')} width="min(960px, 94vw)"
      open={!!plan && detailScope.selected} loading={detailLoading} destroyOnHidden onClose={() => {reads.cancel('plan'); setPlan(undefined);}}>
      {plan && <>
        <Descriptions size="small" column={2} style={{marginBottom: 16}}>
          <Descriptions.Item label={t('dna.runtime.column.physicalModelId', '模型 ID')}>
            {plan.physicalModelId}
          </Descriptions.Item>
          <Descriptions.Item label={t('dna.runtime.column.targetVersion', '目标版本')}>
            {plan.targetVersion}
          </Descriptions.Item>
          <Descriptions.Item label={t('dna.runtime.column.dialectId', '方言')}>
            {plan.dialectId}
          </Descriptions.Item>
          <Descriptions.Item label={t('dna.runtime.column.risk', '风险')}>
            <StatusTag {...runtimeStatus(plan.risk, t)}/>
          </Descriptions.Item>
        </Descriptions>
        <DataTable<Step> storageKey="dna.runtime.plan.steps" rowKey="sequence"
          query={{mode: 'local', scope: 'complete', fields: {
            sequence: {type: 'number'}, operation: {type: 'string'}, logicalObject: {type: 'string'},
            risk: {type: 'string'}, status: {type: 'string'}, attempts: {type: 'number'},
            errorCode: {type: 'string'},
          }}} columns={detailColumns} dataSource={plan.steps} pagination={false}/>
      </>}
    </Drawer>

    <Drawer title={t('dna.runtime.migration.title', '迁移详情')} width="min(960px, 94vw)"
      open={!!publication && detailScope.selected} loading={detailLoading} destroyOnHidden
      onClose={() => {reads.cancel('migration'); setPublication(undefined);}}>
      {publication && <>
        <Descriptions size="small" column={2} style={{marginBottom: 16}}>
          <Descriptions.Item label={t('dna.runtime.column.status', '状态')}>
            <StatusTag {...runtimeStatus(publication.status, t)}/>
          </Descriptions.Item>
          <Descriptions.Item label={t('dna.runtime.column.risk', '风险')}>
            {publication.risk ? <StatusTag {...runtimeStatus(publication.risk, t)}/> : '—'}
          </Descriptions.Item>
          <Descriptions.Item label={t('dna.runtime.column.fromVersion', '源版本')}>
            {publication.fromVersion ?? '—'}
          </Descriptions.Item>
          <Descriptions.Item label={t('dna.runtime.column.targetVersion', '目标版本')}>
            {publication.targetVersion}
          </Descriptions.Item>
        </Descriptions>
        <DataTable<Step> storageKey="dna.runtime.migration.steps" rowKey="sequence"
          query={{mode: 'local', scope: 'complete', fields: {
            sequence: {type: 'number'}, operation: {type: 'string'}, logicalObject: {type: 'string'},
            risk: {type: 'string'}, status: {type: 'string'}, attempts: {type: 'number'},
            errorCode: {type: 'string'},
          }}} columns={detailColumns} dataSource={publication.steps} pagination={false}/>
      </>}
    </Drawer>
  </>;
}
