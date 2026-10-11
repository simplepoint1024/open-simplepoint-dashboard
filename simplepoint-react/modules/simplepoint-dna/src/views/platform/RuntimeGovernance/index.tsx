import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {SimpleTablePageRequest} from '@simplepoint/components/SimpleTable/types';
import DataTable from '@simplepoint/components/DataTable';
import {get, post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {App, Descriptions, Drawer, Tabs, Tag} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {formatDateTime, resolveErrorMessage} from '../shared';

const config = api['platform.dna-runtime'];
const endpoint = config.baseUrl;

type Model = {
  id: string; displayName: string; appId: string; formId: string; status: string;
  latestVersion: number; activeVersion?: number; lockVersion: number; updatedAt: string;
};
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

const statusColor = (value?: string) => {
  if (value === 'ACTIVE' || value === 'SUCCEEDED') return 'success';
  if (value === 'FAILED') return 'error';
  if (value === 'DESTRUCTIVE') return 'red';
  if (value === 'CONDITIONAL') return 'orange';
  return 'processing';
};

const recoverableStatuses = new Set(['VALIDATING', 'READY', 'MIGRATING', 'VERIFYING']);

const stepColumns = (t: (key: string, fallback?: string) => string) => [
  {title: t('dna.runtime.column.sequence', '序号'), dataIndex: 'sequence', width: 80},
  {title: t('dna.runtime.column.operation', '操作'), dataIndex: 'operation', width: 180},
  {title: t('dna.runtime.column.logicalObject', '逻辑对象'), dataIndex: 'logicalObject', width: 220},
  {title: t('dna.runtime.column.risk', '风险'), dataIndex: 'risk', width: 130,
    render: (value: string) => <Tag color={statusColor(value)}>{value}</Tag>},
  {title: t('dna.runtime.column.status', '状态'), dataIndex: 'status', width: 130,
    render: (value?: string) => value ? <Tag color={statusColor(value)}>{value}</Tag> : '—'},
  {title: t('dna.runtime.column.attempts', '尝试次数'), dataIndex: 'attempts', width: 110},
  {title: t('dna.runtime.column.errorCode', '错误码'), dataIndex: 'errorCode', width: 260,
    render: (value?: string) => value || '—'},
];

export default function RuntimeGovernance() {
  const {t, ensure, locale} = useI18n();
  const {message, modal} = App.useApp();
  const [revision, setRevision] = useState(0);
  const [plan, setPlan] = useState<Plan>();
  const [publication, setPublication] = useState<Publication>();
  const [detailLoading, setDetailLoading] = useState(false);

  useEffect(() => { void ensure(config.i18nNamespaces); }, [ensure, locale]);

  const loadModels = useMemo(() => pageLoader<Model>('/models'), []);
  const loadVersions = useMemo(() => pageLoader<Version>('/versions'), []);
  const loadMigrations = useMemo(() => pageLoader<Migration>('/migrations'), []);
  const loadAudits = useMemo(() => pageLoader<Audit>('/audits'), []);

  const openPlan = useCallback(async (model: Model) => {
    setDetailLoading(true);
    try {
      setPlan(await get<Plan>(endpoint + '/models/' + model.id + '/plan', {
        targetVersion: model.latestVersion,
      }, {notifyOnError: false}));
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.runtime.loadPlanFailed', '迁移计划加载失败')));
    } finally {
      setDetailLoading(false);
    }
  }, [message, t]);

  const openMigration = useCallback(async (migration: Migration) => {
    setDetailLoading(true);
    try {
      setPublication(await get<Publication>(endpoint + '/migrations/' + migration.id,
        undefined, {notifyOnError: false}));
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.runtime.loadMigrationFailed', '迁移详情加载失败')));
    } finally {
      setDetailLoading(false);
    }
  }, [message, t]);

  const retryMigration = useCallback((migration: Migration) => {
    if (migration.status !== 'FAILED') {
      message.warning(t('dna.runtime.retryOnlyFailed', '只有失败的迁移可以重试'));
      return;
    }
    modal.confirm({
      title: t('dna.runtime.confirmRetry', '确认重试这个迁移？'),
      onOk: async () => {
        try {
          await post(endpoint + '/migrations/' + migration.id + '/retry', undefined, {
            params: {expectedVersion: migration.expectedModelVersion}, notifyOnError: false,
          });
          setRevision(value => value + 1);
          message.success(t('dna.runtime.retryStarted', '迁移重试已完成'));
        } catch (error) {
          message.error(resolveErrorMessage(error, t('dna.runtime.retryFailed', '迁移重试失败')));
          throw error;
        }
      },
    });
  }, [message, modal, t]);

  const recoverMigration = useCallback((migration: Migration) => {
    if (!recoverableStatuses.has(migration.status)) {
      message.warning(t('dna.runtime.recoverOnlyRunning', '只有长时间无活动的进行中迁移可以恢复'));
      return;
    }
    modal.confirm({
      title: t('dna.runtime.confirmRecover', '确认隔离并终止这个无活动迁移？'),
      content: t('dna.runtime.recoverHint', '达到服务端无活动阈值后，迁移会转为失败；请检查目标表后再执行重试。'),
      okButtonProps: {danger: true},
      onOk: async () => {
        try {
          await post(endpoint + '/migrations/' + migration.id + '/recover', undefined, {
            params: {expectedVersion: migration.expectedModelVersion}, notifyOnError: false,
          });
          setRevision(value => value + 1);
          message.success(t('dna.runtime.recoverCompleted', '迁移已隔离并转为失败状态'));
        } catch (error) {
          message.error(resolveErrorMessage(error, t('dna.runtime.recoverFailed', '迁移恢复失败')));
          throw error;
        }
      },
    });
  }, [message, modal, t]);

  const commonDateColumns = useMemo(() => ({
    updatedAt: {width: 180, render: (value?: string) => formatDateTime(value)},
    createdAt: {width: 180, render: (value?: string) => formatDateTime(value)},
    startedAt: {width: 180, render: (value?: string) => formatDateTime(value)},
    finishedAt: {width: 180, render: (value?: string) => formatDateTime(value)},
    occurredAt: {width: 180, render: (value?: string) => formatDateTime(value)},
  }), []);
  const statusColumns = useMemo(() => ({
    status: {width: 130, render: (value: string) => <Tag color={statusColor(value)}>{value}</Tag>},
    risk: {width: 130, render: (value?: string) => value
      ? <Tag color={statusColor(value)}>{value}</Tag> : '—'},
    outcome: {width: 130, render: (value: string) => <Tag color={statusColor(value)}>{value}</Tag>},
  }), []);
  const detailColumns = useMemo(() => stepColumns(t), [t]);

  return <>
    <Tabs style={{height: '100%'}} items={[
      {key: 'models', label: t('dna.runtime.models.title', '物理模型'), children:
        <SimpleTable<Model> baseUrl={endpoint + '/models'} name="dna-runtime-models"
          i18nNamespaces={config.i18nNamespaces} loadPage={loadModels}
          queryContext={String(revision)} columnOverrides={{...commonDateColumns, ...statusColumns}}
          customButtonEvents={{view: (_keys, rows) => {if (rows[0]) void openPlan(rows[0]);}}}/>
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

    <Drawer title={t('dna.runtime.plan.title', '结构差异与迁移计划')} width={960}
      open={!!plan} loading={detailLoading} destroyOnHidden onClose={() => setPlan(undefined)}>
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
            <Tag color={statusColor(plan.risk)}>{plan.risk}</Tag>
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

    <Drawer title={t('dna.runtime.migration.title', '迁移详情')} width={960}
      open={!!publication} loading={detailLoading} destroyOnHidden
      onClose={() => setPublication(undefined)}>
      {publication && <>
        <Descriptions size="small" column={2} style={{marginBottom: 16}}>
          <Descriptions.Item label={t('dna.runtime.column.status', '状态')}>
            <Tag color={statusColor(publication.status)}>{publication.status}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label={t('dna.runtime.column.risk', '风险')}>
            {publication.risk ? <Tag color={statusColor(publication.risk)}>{publication.risk}</Tag> : '—'}
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
