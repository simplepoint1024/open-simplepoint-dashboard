import {formatDateTime} from '@simplepoint/shared/format/temporal';
import {Alert, Space, Typography} from 'antd';
import DataTable from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {PublicationInspection as Inspection} from './publication';

/** A bounded aggregate report; business data remains in the independently authorized runtime page. */
export default function PublicationInspection({inspection, onViewData}: {
  inspection?: Inspection | null; onViewData: (pageId: string) => void;
}) {
  const {t} = useI18n();
  if (!inspection?.issues.length) return null;
  return <Space orientation="vertical" style={{width: '100%'}} className="app-publication-inspection">
    <Alert type="warning" showIcon title={t('app.publication.blocked', '现有数据不满足发布要求，页面尚未切换。')}
      description={t('app.publication.repairHint', '请在已发布页面修正数据后重试，或取消本次发布并调整设计。各项统计可能重叠，请勿相加。')}/>
    <Typography.Text type="secondary">{t('app.publication.checkedAt', '检查时间：{time}',
      {time: formatDateTime(inspection.checkedAt)})}</Typography.Text>
    <DataTable rowKey="id" storageKey="app.form.publication.inspection" dataSource={inspection.issues}
      pagination={{pageSize: 10}} settings={false} columns={[
        {key: 'fields', title: t('app.publication.issueFields', '字段'), dataIndex: 'fields', render: (fields: string[]) => fields.join('、')},
        {key: 'kind', title: t('app.publication.issueKind', '问题'), dataIndex: 'kind', render: (kind: string) => kind === 'REQUIRED'
          ? t('app.publication.issueRequired', '缺少必填值') : t('app.publication.issueInvalid', '值不符合允许范围')},
        {key: 'count', title: t('app.publication.issueCount', '涉及记录数'), dataIndex: 'count'},
      ]}/>
    {inspection.repairPageId && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'repair', sort: 0, text: t('app.publication.repair', '查看并修正数据')},
    ]} onButtonEvents={{repair: () => onViewData(inspection.repairPageId!)}}/>}
  </Space>;
}
