import {Alert, Descriptions, Empty, Skeleton, Tag} from 'antd';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {get} from '@simplepoint/shared/api/methods';
import ReferenceLabel from '@simplepoint/components/ReferenceLabel';
import TableActions from '@simplepoint/components/Table/Actions';
import {createIcon} from '@simplepoint/shared/types/icon';

export type OrganizationDetails = {record: Record<string, any>; references: Record<string, {id: string; label: string | null}>};
const fields = ['name', 'code', 'type', 'parentId', 'leaderId', 'enabled', 'sort', 'description'];

/** Reads a full authorized record, never the deliberately smaller tree row or another selected node's cache. */
export default function OrganizationInfoPanel({organizationId, baseUrl}: {organizationId?: string; baseUrl: string}) {
  const {t} = useI18n();
  const schema = useSchema(baseUrl, {enabled: !!organizationId});
  const details = useOwnedData<OrganizationDetails>(['organization-detail', organizationId],
    ({signal}) => get(`${baseUrl}/${encodeURIComponent(organizationId!)}`, undefined, {signal}),
    {enabled: !!organizationId, retry: false});
  const properties = schema.data?.schema.properties;
  const failed = schema.isError || details.isError;
  return <section className="organization-info-panel" aria-label={t('organizations.info', '组织信息')}>
    <div className="organization-info-heading">{createIcon('ApartmentOutlined')}<span>{t('organizations.info', '组织信息')}</span></div>
    {!organizationId ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('organizations.selectInfo', '请从左侧选择组织')}/> : failed
      ? <Alert type="error" showIcon title={t('organizations.infoFailed', '组织信息加载失败')} action={<TableActions selectedKeys={[]} selectedRows={[]}
          buttons={[{key: 'retry', sort: 0, icon: 'ReloadOutlined', text: t('table.retry', '重试')}]}
          onButtonEvents={{retry: () => {void schema.refetch(); void details.refetch();}}}/>}/>
      : !details.data || !properties ? <Skeleton active paragraph={{rows: 3}}/>
        : <Descriptions size="small" column={{xs: 1, sm: 2, md: 2}} items={fields.filter(field =>
          properties[field] && Object.prototype.hasOwnProperty.call(details.data.record, field)).map(field => {
          const definition = properties[field] as any, value = details.data.record[field];
          const choice = definition.oneOf?.find((entry: any) => Object.is(entry.const, value));
          return {key: field, label: definition.title ?? '', span: field === 'description' ? 2 : 1,
            children: value == null || value === '' ? '—' : field === 'enabled' && typeof value === 'boolean'
              ? <Tag color={value ? 'success' : 'default'}>{value ? t('common.enabled', '启用') : t('common.disabled', '停用')}</Tag>
              : <ReferenceLabel value={choice?.title ?? value} projection={details.data.references[field]}/>};
        })}/>}
  </section>;
}
