import {useEffect, useMemo, useRef, useState} from 'react';
import {Checkbox, Empty, Space, Tag} from 'antd';
import AssignmentTable from '@simplepoint/components/AssignmentTable';
import type {ColumnsType} from '@simplepoint/components/DataTable';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {useActionFeedback} from '@simplepoint/shared/hooks/useActionFeedback';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import type {RequestOptions} from '@simplepoint/shared/api/client';
import api from '@/api';
import {type JdbcUserDataSourceItem, type JdbcUserGrant, fetchAuthorize,
  fetchUnauthorized, updateGrantPermissions} from '@/api/jdbcUsers';

export interface JdbcUserDataSourceConfigProps {
  userId: string | null;
  canAuthorize: boolean;
  canEditPermissions: boolean;
  canUnauthorize: boolean;
  onChanged?: () => void;
  onBusyChange?: (busy: boolean) => void;
}

type AssignmentRow = JdbcUserDataSourceItem & JdbcUserGrant & {
  catalogName?: string;
  catalogCode?: string;
};
const OPERATIONS = ['METADATA', 'QUERY', 'EXPLAIN', 'DML', 'DDL'] as const;
const COLORS = {METADATA: 'blue', QUERY: 'green', EXPLAIN: 'cyan', DML: 'orange', DDL: 'red'};
const {baseUrl} = api['platform.dna-federation-jdbc-users'];

export default function DataSourceConfig({userId, canAuthorize, canEditPermissions, canUnauthorize,
  onChanged, onBusyChange}: JdbcUserDataSourceConfigProps) {
  const {t} = useI18n();
  const run = useActionFeedback();
  const parent = useMemo(() => ({userId: userId ?? ''}), [userId]);
  const schema = useSchema(`${baseUrl}/assigned`, {enabled: !!userId, retry: false}, parent);
  const permissionField = schema.data?.schema.properties?.operationPermissions;
  const mayEditPermissions = canEditPermissions && !!permissionField
    && typeof permissionField === 'object' && permissionField.readOnly !== true;
  const [revision, setRevision] = useState(0);
  const [writing, setWriting] = useState(false);
  const lock = useRef(false);
  const mounted = useRef(true);
  usePageRetention(!writing, writing);
  useEffect(() => {mounted.current = true; return () => {mounted.current = false;};}, []);

  const begin = () => {
    lock.current = true;
    setWriting(true);
    onBusyChange?.(true);
  };
  const finish = () => {
    lock.current = false;
    if (mounted.current) {setWriting(false); onBusyChange?.(false);}
  };
  const changed = () => {
    if (mounted.current) {setRevision(value => value + 1); onChanged?.();}
  };
  const move = async (selected: boolean, keys: string[], options: RequestOptions) => {
    if (!userId || lock.current || (selected ? !canUnauthorize : !canAuthorize)) {
      throw new Error(t('error.operationFailed', '操作失败，请重试'));
    }
    begin();
    try {
      const dto = {userId, dataSourceIds: keys};
      await (selected ? fetchUnauthorized(dto, options) : fetchAuthorize(dto, options));
      if (!options.signal?.aborted) changed();
    } finally {finish();}
  };
  const permissionChange = async (grant: AssignmentRow, operation: typeof OPERATIONS[number], checked: boolean) => {
    if (!mayEditPermissions || lock.current || !grant.id || !Array.isArray(grant.operationPermissions)) return;
    begin();
    try {
      const permissions = new Set(grant.operationPermissions);
      if (checked) permissions.add(operation); else permissions.delete(operation);
      const result = await run('jdbcGrant.permissions', () => updateGrantPermissions(grant.id, [...permissions]));
      if (result.status === 'success') changed();
    } finally {finish();}
  };
  const availableColumns: ColumnsType<AssignmentRow> = [
    {key: 'name', dataIndex: 'name', title: t('dna.dataSources.title.name', '名称'), ellipsis: true},
    {key: 'code', dataIndex: 'code', title: t('dna.dataSources.title.code', '编码'), ellipsis: true},
    {key: 'databaseProductName', dataIndex: 'databaseProductName',
      title: t('dna.dataSources.title.databaseProductName', '数据库产品'), ellipsis: true},
  ];
  const operationLabels = {
    METADATA: t('dna.federation.jdbcUsers.page.permission.METADATA', '元数据'),
    QUERY: t('dna.federation.jdbcUsers.page.permission.QUERY', '查询'),
    EXPLAIN: t('dna.federation.jdbcUsers.page.permission.EXPLAIN', '执行计划'),
    DML: t('dna.federation.jdbcUsers.page.permission.DML', 'DML'),
    DDL: t('dna.federation.jdbcUsers.page.permission.DDL', 'DDL'),
  };
  const assignedColumns = ({disabled}: {disabled: boolean}): ColumnsType<AssignmentRow> => [
    {key: 'catalogName', dataIndex: 'catalogName',
      title: t('dna.federation.jdbcUsers.title.catalogName', '数据源名称'), ellipsis: true},
    {key: 'catalogCode', dataIndex: 'catalogCode',
      title: t('dna.federation.jdbcUsers.title.catalogCode', '数据源编码'), ellipsis: true},
    {key: 'operationPermissions', title: t('dna.federation.jdbcUsers.page.label.operationPermissions', '操作权限'),
      width: 300, render: (_, grant) => !Array.isArray(grant.operationPermissions) ? '—' :
        <Space size={4} wrap>{OPERATIONS.map(operation => {
          const checked = grant.operationPermissions!.includes(operation);
          return <Checkbox key={operation} checked={checked} disabled={!mayEditPermissions || writing || disabled}
            onChange={event => {void permissionChange(grant, operation, event.target.checked);}}>
            <Tag color={checked ? COLORS[operation] : 'default'} style={{marginInlineEnd: 0}}>
              {operationLabels[operation]}
            </Tag>
          </Checkbox>;
        })}</Space>},
  ];
  if (!userId) return <Empty description={t('dna.federation.jdbcUsers.page.empty.selectUser', '请先选择一个系统用户')}/>;
  return <AssignmentTable<AssignmentRow> endpoint={`${baseUrl}/items`} assignedEndpoint={`${baseUrl}/assigned`}
    parent={parent} storageKey="dna.jdbc-users.dataSources" columns={availableColumns}
    assignedColumns={assignedColumns} itemKey="id" assignedItemKey="catalogId"
    canAssign={canAuthorize} canUnassign={canUnauthorize} disabled={writing}
    queryKey={[revision]} onMove={move}
    labels={{available: t('dna.federation.jdbcUsers.page.card.available', '可用数据源'),
      assigned: t('dna.federation.jdbcUsers.page.card.authorized', '已授权数据源'),
      assign: t('dna.federation.jdbcUsers.page.button.authorize', '授权'),
      unassign: t('dna.federation.jdbcUsers.page.button.unauthorize', '取消授权')}}/>;
}
