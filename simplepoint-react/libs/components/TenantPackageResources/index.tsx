import {DEFAULT_TABLE_PAGE_SIZE} from '@simplepoint/shared/types/pagination';
import {useCallback, useEffect, useState} from 'react';
import {Alert} from 'antd';
import DataTable, {type ColumnsType} from '../DataTable';
import StatusTag, {type StatusTone} from '../StatusTag';
import {get} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOwnedReads} from '@simplepoint/shared/hooks/useOwnedReads';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {formatDateTime} from '@simplepoint/shared/format/temporal';
import {normalizePage, type Page, type RawPage} from '@simplepoint/shared/types/request';
import type {PackageResource} from '@simplepoint/shared/types/tenantPackage';

const tones: Record<string, StatusTone> = {ACTIVE: 'success', UNLIMITED: 'success', NOT_STARTED: 'warning', EXPIRED: 'neutral', NONE: 'neutral'};

/** Each opened instance owns its requests; a retained page never commits another instance's detail. */
export default function PackageResources({baseUrl, instanceId}: {baseUrl: string; instanceId: string}) {
  const {t} = useI18n();
  const operation = useOperationScope(JSON.stringify([baseUrl, instanceId]));
  const {run, cancel, errors, loading, active} = useOwnedReads(operation, true);
  const [paging, setPaging] = useState({page: 1, size: DEFAULT_TABLE_PAGE_SIZE});
  const [data, setData] = useState<Page<PackageResource>>();
  const load = useCallback(() => {
    cancel('resources');
    setData(undefined);
    void run('resources', signal => get<RawPage<PackageResource>>(
      `${baseUrl}/${encodeURIComponent(instanceId)}/resources`,
      {page: paging.page - 1, size: paging.size}, {signal}), result => setData(normalizePage(result, paging.size)));
  }, [cancel, run, baseUrl, instanceId, paging]);
  useEffect(() => {if (active && operation.selected) load(); return () => cancel('resources');}, [load, active, operation.selected, cancel]);
  const labels: Record<string, string> = {
    activatedAt: t('tenant.packages.activatedAt'), creditedExpiresAt: t('tenant.packages.creditedExpiresAt'),
    currentStartsAt: t('tenant.packages.currentStartsAt'), currentExpiresAt: t('tenant.packages.currentExpiresAt'), revokedAt: t('tenant.packages.revokedAt'),
    ACTIVE: t('tenant.packages.entitlement.ACTIVE'), UNLIMITED: t('tenant.packages.entitlement.UNLIMITED'),
    NOT_STARTED: t('tenant.packages.entitlement.NOT_STARTED'), EXPIRED: t('tenant.packages.entitlement.EXPIRED'), NONE: t('tenant.packages.entitlement.NONE'),
  };
  const timeColumn = (key: keyof PackageResource): ColumnsType<PackageResource>[number] => ({
    key, dataIndex: key, width: 200, title: labels[key], render: value => formatDateTime(value),
  });
  const columns: ColumnsType<PackageResource> = [
    {key: 'resourceName', dataIndex: 'resourceName', width: 180, title: t('tenant.packages.resourceName')},
    {key: 'resourceCode', dataIndex: 'resourceCode', width: 220, title: t('tenant.packages.resourceCode')},
    {key: 'durationDays', dataIndex: 'durationDays', width: 160, title: t('tenant.packages.durationDays'),
      render: value => value == null ? t('tenant.packages.entitlement.UNLIMITED') : value},
    {key: 'currentState', dataIndex: 'currentState', width: 150, title: t('tenant.packages.currentState'), render: value =>
      <StatusTag label={labels[value] ?? value ?? '—'} tone={tones[value] ?? 'neutral'}/>},
    timeColumn('activatedAt'), timeColumn('creditedExpiresAt'), timeColumn('currentStartsAt'),
    {...timeColumn('currentExpiresAt'), render: (value, row) => row.currentState === 'UNLIMITED'
      ? t('tenant.packages.entitlement.UNLIMITED') : formatDateTime(value)}, timeColumn('revokedAt'),
  ];
  return <>
    <Alert type="info" showIcon title={t('tenant.packages.resourceNotice')} style={{marginBottom: 12}}/>
    {!!errors.resources && <Alert type="error" showIcon title={resolveApiErrorMessage(errors.resources, t('table.loadFail'))} style={{marginBottom: 12}}/>}
    <div style={{flex: 1, minHeight: 0}}>
      <DataTable<PackageResource> rowKey="id" columns={columns} dataSource={data?.content ?? []}
        fitContainer defaultOrder="provided" selectable={false} cards={false} loading={loading.length > 0}
        storageKey="tenant-package-resources" scroll={{x: 1750}} refresh={load}
        pagination={{current: paging.page, pageSize: paging.size, total: data?.page.totalElements ?? 0,
          showSizeChanger: true, pageSizeOptions: [50, 100, 200, 500],
          onChange: (page, size) => setPaging({page: size === paging.size ? page : 1, size})}}/>
    </div>
  </>;
}
