import {lazy, Suspense, useEffect, useState} from 'react';
import {Alert, Descriptions, Drawer, Empty, Segmented, Space, Spin, Timeline, Typography} from 'antd';
import {useData} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {workflowApi} from '@simplepoint/shared/workflow/api';
import TableActions from '../Table/Actions';
import {WorkflowStatus} from './Status';
import {initialProgressSelection} from './progressSelection';

const ProcessStatusDiagram = lazy(() => import('../ProcessStatusDiagram'));
const ActionTimeline = lazy(() => import('./ActionTimeline'));

export default function ProgressDrawer(props: {id: string; onClose: () => void}) {
  const scope = useQueryScope();
  return <ProgressChain key={JSON.stringify([scope, props.id])} {...props}/>;
}

function ProgressChain({id, onClose}: {id: string; onClose: () => void}) {
  const [path, setPath] = useState([id]);
  const current = path[path.length - 1];
  return <ProgressSession key={current} id={current} onClose={onClose}
    onNavigate={next => setPath(previous => [...previous, next])}
    onBack={path.length > 1 ? () => setPath(previous => previous.slice(0, -1)) : undefined}/>;
}

function ProgressSession({id, onClose, onNavigate, onBack}: {
  id: string; onClose: () => void; onNavigate: (id: string) => void; onBack?: () => void;
}) {
  const {t, locale} = useI18n(), selected = usePageSelected();
  const [node, setNode] = useState<string>(), [page, setPage] = useState(0), [activeOnly, setActiveOnly] = useState(false);
  const [section, setSection] = useState<'nodes' | 'actions'>('nodes');
  const query = useData(['workflow', 'progress', id], ({signal}) => workflowApi.progress(id, signal), {retry: false});
  const progress = query.data, diagram = progress?.diagram;
  useEffect(() => {
    if (node || query.error) return;
    const selection = initialProgressSelection(diagram, query.isFetching);
    if (selection) {setNode(selection.node); setActiveOnly(selection.activeOnly);}
  }, [diagram, node, query.isFetching, query.error]);
  useEffect(() => {
    if (diagram && !query.isFetching && !query.error && node && activeOnly && !diagram.activeActivityIds.includes(node)) {
      setActiveOnly(false); setPage(0);
    }
  }, [diagram, node, activeOnly, query.isFetching, query.error]);
  const history = useData(['workflow', 'node-history', id, node, page, activeOnly],
    ({signal}) => workflowApi.nodeHistory(id, node!, page, activeOnly, signal), {enabled: Boolean(node) && section === 'nodes', retry: false});
  const pick = (value: string) => {setNode(value); setPage(0); setActiveOnly(Boolean(diagram?.activeActivityIds.includes(value)));};
  const date = (value?: string) => value ? new Date(value).toLocaleString(locale) : '—';
  const unavailable = t('workflow.identity.unavailable', '姓名不可用');
  const name = diagram?.activities.find(activity => activity.activityId === node)?.activityName;
  return <Drawer rootClassName="workflow-progress-detail" title={progress?.matter.title ?? t('workflow.diagram.title', '流程进度')}
    open={selected} width="min(1200px, 96vw)" onClose={onClose} destroyOnHidden
    extra={onBack && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'back', sort: 1,
      text: t('workflow.resubmit.back', '返回刚才的事项'), onClick: onBack}]}/> }>
    {query.isFetching && <Spin/>}
    {query.error && <Alert showIcon type="error" title={resolveApiErrorMessage(query.error, t('workflow.diagram.loadFailed', '流程状态图加载失败'))}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 1, text: t('workflow.retry', '重试')}]}
        onButtonEvents={{retry: () => void query.refetch()}}/>}/>}
    {progress && diagram && <>
      <Descriptions size="small" column={{xs: 1, sm: 2, md: 3}} items={[
        {key: 'definition', label: t('workflow.definition.name', '流程名称'), children: progress.matter.definitionName},
        {key: 'version', label: t('workflow.matter.definitionVersion', '流程版本'), children: `v${progress.matter.definitionVersion}`},
        {key: 'status', label: t('workflow.inbox.instanceStatus', '流程状态'), children: <WorkflowStatus value={progress.matter.status}/>},
        {key: 'starter', label: t('workflow.inbox.starterName', '发起人'), children: progress.matter.starterName || unavailable},
        {key: 'started', label: t('workflow.matter.startedAt', '发起时间'), children: date(progress.matter.startedAt)},
        {key: 'ended', label: t('workflow.matter.endedAt', '结束时间'), children: date(progress.matter.endedAt)},
      ]}/>
      <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        ...(progress.matter.previousInstanceId ? [{key: 'previousMatter', sort: 1,
          text: t('workflow.resubmit.previous', '查看原事项'), onClick: () => onNavigate(progress.matter.previousInstanceId!)}] : []),
        ...(progress.matter.nextInstanceId ? [{key: 'nextMatter', sort: 2,
          text: t('workflow.resubmit.next', '查看重新发起的事项'), onClick: () => onNavigate(progress.matter.nextInstanceId!)}] : []),
      ]}/>
      <Segmented value={section} onChange={value => setSection(value as 'nodes' | 'actions')} options={[
        {value: 'nodes', label: t('workflow.audit.nodes', '审批节点')},
        {value: 'actions', label: t('workflow.audit.title', '流程操作记录')},
      ]}/>
      {section === 'actions' ? <Suspense fallback={<Spin/>}><ActionTimeline id={id} onSelectNode={activity => {
        setNode(activity); setPage(0); setActiveOnly(false); setSection('nodes');
      }}/></Suspense> : <>
      <Typography.Paragraph type="secondary">{t('workflow.diagram.nodeHint', '点击图中的节点，或使用下方节点按钮，查看当前审批人与逐次处理记录。')}</Typography.Paragraph>
      {diagram.historyTruncated && <Alert showIcon type="info" title={t('workflow.diagram.boundedHistory', '画布显示最近 500 条执行活动；点击节点可以分页查看完整记录。')}/>}
      <div className="workflow-progress-layout">
      <Suspense fallback={<Spin/>}><ProcessStatusDiagram xml={diagram.bpmnXml} activities={diagram.activities}
        activeActivityIds={diagram.activeActivityIds} selectedActivityId={node} onSelectActivity={pick} fitActive
        height="clamp(360px, 58vh, 520px)"/></Suspense>
      {node && <section className="workflow-node-history" aria-live="polite" aria-label={t('workflow.diagram.nodeHistory', '节点处理记录')}>
        <Space wrap><Typography.Title level={5}>{name || t('workflow.diagram.nodeHistory', '节点处理记录')}</Typography.Title>
          <Segmented value={activeOnly ? 'active' : 'all'} onChange={value => {setActiveOnly(value === 'active'); setPage(0);}} options={[
            {value: 'active', label: t('workflow.diagram.currentApproval', '当前审批')},
            {value: 'all', label: t('workflow.diagram.allExecutions', '全部执行记录')},
          ]}/></Space>
        {history.isFetching && <Spin/>}
        {history.error && <Alert showIcon type="error" title={resolveApiErrorMessage(history.error, t('workflow.diagram.nodeLoadFailed', '节点记录加载失败'))}
          action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 1, text: t('workflow.retry', '重试')}]}
            onButtonEvents={{retry: () => void history.refetch()}}/>}/>}
        {history.data && !history.error && <>
          {!history.data.content.length && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={activeOnly
            ? t('workflow.diagram.noCurrentApproval', '此节点当前没有待处理的审批') : t('workflow.diagram.noExecutions', '此节点尚未产生执行记录')}/>}
          <Timeline items={history.data.content.map(item => ({key: item.id,
            content: <>
              <Space wrap className="workflow-event-heading"><strong>{item.activityName || t('workflow.diagram.nodeHistory', '节点处理记录')}</strong>
                <WorkflowStatus value={item.outcome === 'PENDING' ? item.status : item.outcome || item.status}/></Space>
              <Descriptions size="small" column={1} items={[
                {key: 'arrival', label: t('workflow.inbox.createdAt', '到达时间'), children: date(item.startedAt)},
                {key: 'end', label: t('workflow.inbox.handledAt', '处理时间'), children: date(item.endedAt)},
                ...(item.taskId ? [
                  {key: 'assignee', label: t('workflow.task.assignee', '办理人'), children: item.assigned
                    ? item.assigneeName || unavailable : t('workflow.task.unclaimed', '待认领')},
                  ...(item.endedAt ? [{key: 'actor', label: t('workflow.inbox.actorName', '实际操作人'), children: item.actorName || unavailable}] : []),
                  ...(item.candidateUsers.length ? [{key: 'candidates', label: t('workflow.designer.candidateUsers', '候选用户'),
                    children: item.candidateUsers.map(value => value.name || unavailable).join('、')}] : []),
                  ...(item.candidateRoles.length ? [{key: 'roles', label: t('workflow.designer.candidateGroups', '候选角色'),
                    children: item.candidateRoles.map(value => value.name || t('workflow.identity.roleUnavailable', '角色不可用')).join('、')}] : []),
                ] : []),
              ]}/>
              {item.comment && <p className="workflow-node-comment">{item.comment}</p>}
            </>,
          }))}/>
          {history.data.totalElements > 20 && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
            {key: 'previous', sort: 1, text: t('workflow.diagram.previousRecords', '上一页记录'), disabled: page === 0},
            {key: 'next', sort: 2, text: t('workflow.diagram.nextRecords', '下一页记录'), disabled: (page + 1) * 20 >= history.data.totalElements},
          ]} onButtonEvents={{previous: () => setPage(value => value - 1), next: () => setPage(value => value + 1)}}/>}
        </>}
      </section>}
      </div>
      </>}
    </>}
  </Drawer>;
}
