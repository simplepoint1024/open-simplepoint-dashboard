import {useMemo, useRef, useState} from 'react';
import {Alert, Descriptions, Modal, Spin} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import Table from '@simplepoint/components/DataTable';
import {get} from '@simplepoint/shared/api/methods';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useOwnedReads} from '@simplepoint/shared/hooks/useOwnedReads';
import {usePermissionLabels} from '@simplepoint/shared/hooks/usePermissionLabels';
import {contextNames, expressionLabels, expressionWords} from '@simplepoint/shared/i18n/permissionLabels';
import api from '@/api';
import {effectiveScope, type EffectiveScope} from './effectiveModel';

export default function EffectiveScopePreview({isBlocked, onOpenChange}: {isBlocked: () => boolean; onOpenChange: (open: boolean) => void}) {
  const operation = useOperationScope(), reads = useOwnedReads(operation, true);
  const [open, setOpen] = useState(false);
  const opened = useRef(false), [attempted, setAttempted] = useState(false);
  const [data, setData] = useState<EffectiveScope>();
  const {labels, t, data: names, error: namesError, isFetching: namesLoading, refetch: retryNames} = usePermissionLabels(open);
  const channel = 'effective-permissions', isFetching = reads.loading.includes(channel), error = reads.errors[channel];
  const refresh = () => {
    if (!opened.current || !operation.current() || !operation.isSelected() || !reads.active || reads.isBusy()) return;
    setData(undefined); setAttempted(true); reads.clearErrors(channel);
    void reads.run(channel, async signal => {
      const result = effectiveScope(await get(`${api['rbac-data-scopes'].baseUrl}/effective`, undefined, {signal, notifyOnError: false}));
      if (!result) throw Error('Invalid effective policy response'); return result;
    }, setData);
    if (namesError) void retryNames();
  };
  const show = () => {
    if (opened.current || isBlocked() || !operation.current() || !operation.isSelected() || !reads.active) return;
    opened.current = true; setOpen(true); onOpenChange(true); refresh();
  };
  const close = () => {
    if (!operation.current() || !operation.isSelected()) return;
    opened.current = false; reads.cancel(channel); reads.clearErrors(channel);
    setOpen(false); setData(undefined); onOpenChange(false);
  };
  const scope = (code: string, rule: NonNullable<EffectiveScope['resourceDataScopes']>[string]) => {
    const fields = Object.assign({}, ...(names?.pages[code]?.models ?? []).map(model => model.properties));
    const localized = expressionLabels(Object.fromEntries(Object.entries(fields).map(([name, field]) => [name, labels.field(name, field as any, code)])),
      Object.fromEntries(contextNames.map(name => [name, labels.context(name)])), expressionWords(t));
    return [rule.type === 'DENY' && rule.expressions?.length ? '' : labels.scope(rule.type),
      ...(rule.expressions ?? []).map(localized.encode)].filter(Boolean).join(` ${t('permissions.expression.OR', '或')} `);
  };
  const dataRows = useMemo(() => Object.entries(data?.resourceDataScopes ?? {}).map(([resource, rule]) => ({resource, scope: scope(resource, rule),
    title: labels.page(resource), organizations: labels.organizations(rule.departmentIds), self: rule.includeSelf ? t('common.yes', '是') : t('common.no', '否')})), [data, names, labels, t]);
  const resourceFields = useMemo(() => Object.entries(data?.resourceFieldPermissions ?? {}).flatMap(([resource, fields]) => Object.entries(fields).map(([field, access]) => ({
    key: `${resource}:${field}`, resource: labels.page(resource), field: labels.fieldKey(field, resource), access: labels.access(access)}))), [data, labels]);
  const legacyFields = useMemo(() => Object.entries(data?.fieldPermissions ?? {}).map(([field, access]) => ({key: field, field: labels.fieldKey(field), access: labels.access(access)})), [data, labels]);
  return <>
    <div style={{marginBottom: 12}}><TableActions selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'preview', sort: 1, text: t('data-scopes.preview.title', '当前生效权限'), disabled: isBlocked() || !operation.selected || open},
    ]} onButtonEvents={{preview: show}}/></div>
    {open && operation.selected && reads.active && <Modal rootClassName="data-scope-effective-preview" title={t('data-scopes.preview.title', '当前生效权限')} open destroyOnHidden onCancel={close} width={860}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'close', sort: 0, text: t('data-scopes.preview.close', '关闭'), onClick: close},
        {key: 'refresh', sort: 1, text: t('common.refresh', '刷新'), loading: isFetching, disabled: isFetching, onClick: refresh},
      ]}/>}>
      <Alert type="info" showIcon title={t('permissions.previewHint', '展示当前会话已保存的有效权限；全部数据仍受工作空间隔离约束。')} />
      {(error || namesError) ? <Alert type="error" title={t('data-scopes.preview.failed', '权限预览加载失败')} /> : <Spin spinning={isFetching || namesLoading}>
        {attempted && !data && !isFetching && <Alert type="warning" title={t('data-scopes.preview.interrupted', '读取已中断，请刷新后查看当前权限')} />}
        {data && <>
          <Descriptions column={1} items={[
            {key: 'user', label: t('data-scopes.preview.user', '用户'), children: names?.userName || t('permissions.currentUser', '当前用户')},
            {key: 'tenant', label: t('data-scopes.preview.tenant', '租户'), children: names?.tenantName || t('permissions.currentTenant', '当前工作空间')},
            {key: 'role', label: t('data-scopes.preview.role', '角色'), children: data.roleId ? names?.roleName || t('permissions.currentRole', '当前角色') : t('data-scopes.preview.allRoles', '全部有效角色合并')},
            {key: 'scope', label: t('data-scopes.preview.scope', '行范围'), children: labels.scope(data.dataScopeType)},
            {key: 'depts', label: t('permissions.organizations', '组织'), children: labels.organizations(data.deptIds)},
            {key: 'self', label: t('data-scopes.preview.self', '额外包含本人数据'), children: data.includeSelf ? t('common.yes', '是') : t('common.no', '否')},
          ]} />
          <Table size="small" rowKey="resource" pagination={{pageSize: 10}} storageKey="rbac.effective-page-data-permissions"
            dataSource={dataRows}
            query={{mode: 'local', scope: 'complete', fields: {
              title: {type: 'string'}, scope: {type: 'string'}, organizations: {type: 'string'}, self: {type: 'string'},
            }}}
            columns={[
              {title: t('data-scopes.preview.resource', '页面资源'), dataIndex: 'title'},
              {title: t('data-scopes.preview.scope', '行范围'), dataIndex: 'scope'},
              {title: t('permissions.organizations', '组织'), dataIndex: 'organizations'},
              {title: t('data-scopes.preview.self', '额外包含本人数据'), dataIndex: 'self'},
            ]} />
          <Table size="small" rowKey="key" pagination={{pageSize: 10}} storageKey="rbac.effective-page-field-permissions"
            dataSource={resourceFields}
            query={{mode: 'local', scope: 'complete', fields: {
              resource: {type: 'string'}, field: {type: 'string'}, access: {type: 'string'},
            }}}
            columns={[
              {title: t('data-scopes.preview.resource', '页面资源'), dataIndex: 'resource'},
              {title: t('data-scopes.preview.field', '资源字段'), dataIndex: 'field'},
              {title: t('data-scopes.preview.access', '访问级别'), dataIndex: 'access'},
            ]} />
          <Table size="small" rowKey="key" pagination={{pageSize: 10}} storageKey="rbac.effective-field-permissions"
            dataSource={legacyFields}
            query={{mode: 'local', scope: 'complete', fields: {field: {type: 'string'}, access: {type: 'string'}}}}
            columns={[
              {title: t('data-scopes.preview.field', '资源字段'), dataIndex: 'field'},
              {title: t('data-scopes.preview.access', '访问级别'), dataIndex: 'access'},
            ]} />
        </>}
      </Spin>}
    </Modal>}
  </>;
}
