import {useEffect} from 'react';
import {Alert, Badge, Empty, Spin} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import {useNavigate} from 'react-router';
import {get, useData} from '@simplepoint/shared/api/methods';
import {personalApi, type PersonalOverview as Overview} from '@simplepoint/shared/personal/models';
import type {WorkflowOverview} from '@simplepoint/shared/workflow/api';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageActive} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@/layouts/i18n/useI18n';

/** Workflow cards reuse the personal inbox's server scope; messages retain one shared read state. */
export function PersonalOverview() {
  const {t, ensure, locale} = useI18n();
  const navigate = useNavigate();
  const active = usePageActive();
  const scope = useQueryScope();
  useEffect(() => {void ensure(['workflow']);}, [ensure, locale]);
  const messages = useData(['personal', 'overview'], ({signal}) => get<Overview>(`${personalApi}/overview`, undefined, {signal}), {
    enabled: active, staleTime: 30_000, refetchInterval: active ? 60_000 : false, retry: 1,
  });
  const workflow = useData(['workflow', 'overview'], ({signal}) => get<WorkflowOverview>('/workflow/overview', undefined, {signal}), {
    enabled: active && !!scope[0], staleTime: 30_000, refetchInterval: active && scope[0] ? 60_000 : false, retry: 1,
  });
  const tenantRequired = !scope[0] || workflow.data?.tenantWorkspace === false;
  const unavailable = (allowed?: boolean) => tenantRequired
    ? t('workflow.personal.tenantRequired', '请切换到组织工作空间查看工作流待办')
    : allowed === false ? t('workflow.personal.permissionRequired', '尚未开通此工作流功能，请联系管理员授权') : undefined;
  const cards = [
    {key: 'items', title: t('personal.items', '我的事项'), count: workflow.data?.activeMatters,
      rows: workflow.data?.matters.map(row => ({id: row.id, title: row.title, path: `/my/items?instance=${encodeURIComponent(row.id)}`})),
      unavailable: unavailable(workflow.data?.canViewMatters), query: workflow},
    {key: 'todos', title: t('personal.todos', '我的待办'), count: workflow.data?.pendingTodos,
      rows: workflow.data?.todos.map(row => ({id: row.id, title: row.title,
        path: `/my/todos?task=${encodeURIComponent(row.taskId ?? row.id)}&instance=${encodeURIComponent(row.processInstanceId)}`})),
      unavailable: unavailable(workflow.data?.canViewTodos), query: workflow},
    {key: 'messages', title: t('personal.messages', '我的消息'), count: messages.data?.unreadMessages,
      rows: messages.data?.messages.map(row => ({id: row.id, title: row.title, path: `/my/messages?id=${encodeURIComponent(row.id)}`})),
      unavailable: undefined, query: messages},
  ];
  return <div className="sp-home-personal">
    {cards.map(card => <section className="sp-home-panel" key={card.key} aria-label={card.title}>
      <div className="sp-home-section-heading"><h2>{card.title} {!card.unavailable && !card.query.isError && card.count !== undefined && <Badge count={card.count} showZero />}</h2>
        <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'all', sort: 0, type: 'link', size: 'small',
          text: t('home.viewAll', '查看全部'), onClick: () => navigate(`/my/${card.key}`)}]}/></div>
      {card.unavailable ? <Alert type="info" showIcon title={card.unavailable}/> : card.query.isError ?
        <Alert type="error" showIcon title={t('personal.overviewError', '个人工作摘要加载失败')}
          action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0,
            text: t('personal.retry', '重试'), onClick: () => void card.query.refetch()}]}/>} /> :
      <Spin spinning={card.query.isLoading} delay={200}>
        {!card.query.isLoading && !card.rows?.length && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('personal.nothingPending', '暂无待处理内容')} />}
        <ul className="sp-home-personal-list">{card.rows?.map(row => <li key={row.id}><a href={`#${row.path}`}
          onClick={event => {if (!event.ctrlKey && !event.metaKey && !event.shiftKey && event.button === 0) {event.preventDefault(); navigate(row.path);}}}>{row.title}</a></li>)}</ul>
      </Spin>
      }
    </section>)}
  </div>;
}
