import type {WidgetProps} from '@rjsf/utils';
import {Alert, Space, Typography} from 'antd';
import AssignmentTable from '../../AssignmentTable';
import TableActions from '../../Table/Actions';
import {post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {RawPage} from '@simplepoint/shared/types/request';

type OrganizationRow = {id: string; name: string; code?: string; type?: string; description?: string; enabled?: boolean};
const endpoint = '/common/organizations/selection';
const MAX_DRAFT_IDS = 2000;

/** Selection is a form draft. Neither moving rows nor querying a side persists any grant. */
export default function OrgTreeMultiSelect({value, disabled, readonly, onChange, rawErrors}: WidgetProps) {
  const {t} = useI18n();
  const ids = [...new Set(Array.isArray(value) ? value.map(String) : [])];
  const oversized = ids.length > MAX_DRAFT_IDS;
  return <div style={{border: rawErrors?.length ? '1px solid #ff4d4f' : undefined, borderRadius: 6}}>
    <Space wrap style={{marginBottom: 8}}>
      <Typography.Text>{t('organizations.selector.draftCount', '草稿选择项')}：{ids.length}</Typography.Text>
      <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'clear.selection', sort: 0,
        text: t('organizations.selector.clearDraft', '清空草稿选择'), icon: 'ClearOutlined', danger: true,
        argumentMinSize: 0, argumentMaxSize: 0, disabled: disabled || readonly || ids.length === 0}]}
        onButtonEvents={{'clear.selection': () => {if (!disabled && !readonly) onChange(undefined);}}} />
    </Space>
    {oversized && <Alert type="error" showIcon title={t('organizations.selector.draftLimit', '组织选择草稿最多包含 2000 项，当前内容已保留，请先调整选择')} />}
    {!oversized && <AssignmentTable<OrganizationRow> endpoint={endpoint} parent={{}} itemKey="id"
      storageKey="organizations.form.selection" disabled={disabled || readonly || oversized} queryKey={[ids]} refreshAfterMove={false}
      isRowDisabled={(selected, row) => !selected && row.enabled !== true}
      labels={{available: t('organizations.selector.available', '可选组织'), assigned: t('organizations.selector.selected', '已选组织'),
        assign: t('organizations.selector.addDraft', '加入选择'), unassign: t('organizations.selector.removeDraft', '移除选择')}}
      columns={[
        {key: 'name', dataIndex: 'name', title: t('organizations.title.name', '组织名称')},
        {key: 'code', dataIndex: 'code', title: t('organizations.title.code', '组织编码')},
        {key: 'type', dataIndex: 'type', title: t('organizations.title.type', '类型')},
        {key: 'description', dataIndex: 'description', title: t('organizations.title.description', '描述')},
        {key: 'enabled', dataIndex: 'enabled', title: t('organizations.title.enabled', '启用'),
          render: value => value === true ? t('common.enabled', '启用') : value === false ? t('common.disabled', '停用') : value},
      ]}
      loadPage={({selected, filters, sort, page, size, signal}) => post<RawPage<OrganizationRow>>(endpoint,
        {ids, selected, filters}, {params: {page, size, ...(sort ? {sort} : {})}, signal})}
      onMove={async (selected, keys) => {
        if (disabled || readonly) return;
        const removed = new Set(keys);
        const next = selected ? ids.filter(id => !removed.has(id)) : [...new Set([...ids, ...keys])];
        if (next.length > MAX_DRAFT_IDS) throw new Error(t('organizations.selector.draftLimit', '组织选择草稿最多包含 2000 项，当前内容已保留，请先调整选择'));
        onChange(next.length ? next : undefined);
      }}
    />}
  </div>;
}
