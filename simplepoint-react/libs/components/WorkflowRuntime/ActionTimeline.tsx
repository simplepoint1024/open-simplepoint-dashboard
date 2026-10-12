import {useState} from 'react';
import {Alert, Empty, Space, Spin, Timeline, Typography} from 'antd';
import {useData} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {workflowApi} from '@simplepoint/shared/workflow/api';
import TableActions from '../Table/Actions';
import {WorkflowStatus} from './Status';

/** Platform action journal; engine execution history remains available in the node inspector. */
export default function ActionTimeline({id, onSelectNode}: {id: string; onSelectNode: (id: string) => void}) {
  const {t, locale} = useI18n();
  const [page, setPage] = useState(0);
  const query = useData(['workflow', 'actions', id, page], ({signal}) => workflowApi.actions(id, page, signal), {retry: false});
  const result = query.data;
  return <section className="workflow-node-history" aria-label={t('workflow.audit.title', '流程操作记录')}>
    <Typography.Paragraph type="secondary">{t('workflow.audit.hint', '此处记录平台内的发起、认领、审批和撤回等操作；节点执行情况请在审批节点中查看。')}</Typography.Paragraph>
    {query.isFetching && <Spin/>}
    {query.error && <Alert showIcon type="error" title={resolveApiErrorMessage(query.error, t('workflow.loadFailed', '数据加载失败'))}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 1,
        text: t('workflow.retry', '重试'), onClick: () => void query.refetch()}]}/>}/>}
    {result && !query.error && <>
      {!result.recordedFromStart && <Alert showIcon type="info" title={t('workflow.audit.partial', '此事项早于完整操作记录功能。这里只展示已记录的真实操作，不补造历史审批；旧节点历史仍可在审批节点中查看。')}/>}
      {!result.events.content.length && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('workflow.audit.empty', '暂无平台操作记录')}/>}
      <Timeline items={result.events.content.map(event => ({key: event.id,
        content: <>
          <Space wrap className="workflow-event-heading"><WorkflowStatus value={event.actionType}/><strong>{event.actorName || t('workflow.identity.unavailable', '用户信息不可用')}</strong></Space>
          <Typography.Text type="secondary">{new Date(event.occurredAt).toLocaleString(locale)}</Typography.Text>
          {event.comment && <p className="workflow-node-comment">{event.comment}</p>}
          {event.activityId && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'node', sort: 1, type: 'link',
            text: event.activityName || t('workflow.audit.viewNode', '查看相关节点'), onClick: () => onSelectNode(event.activityId!)}]}/>}
        </>,
      }))}/>
      {result.events.totalElements > 20 && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'previous', sort: 1, text: t('workflow.diagram.previousRecords', '上一页记录'), disabled: page === 0, onClick: () => setPage(value => value - 1)},
        {key: 'next', sort: 2, text: t('workflow.diagram.nextRecords', '下一页记录'), disabled: (page + 1) * 20 >= result.events.totalElements,
          onClick: () => setPage(value => value + 1)},
      ]}/>}
    </>}
  </section>;
}
