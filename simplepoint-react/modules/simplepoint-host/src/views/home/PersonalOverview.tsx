import {Alert, Badge, Empty, Spin} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import {useNavigate} from 'react-router';
import {get, useData} from '@simplepoint/shared/api/methods';
import {personalApi, type PersonalOverview as Overview} from '@simplepoint/shared/personal/models';
import {usePageActive} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@/layouts/i18n/useI18n';

/** One bounded aggregate request for all three cards; hidden pages never poll. */
export function PersonalOverview() {
  const {t} = useI18n();
  const navigate = useNavigate();
  const active = usePageActive();
  const query = useData(['personal', 'overview'], ({signal}) => get<Overview>(`${personalApi}/overview`, undefined, {signal}), {
    staleTime: 30_000, refetchInterval: active ? 60_000 : false, retry: 1,
  });
  const cards = [
    {key: 'items', title: t('personal.items', '我的事项'), count: query.data?.activeItems, rows: query.data?.items, param: 'item'},
    {key: 'todos', title: t('personal.todos', '我的待办'), count: query.data?.pendingTodos, rows: query.data?.todos, param: 'id'},
    {key: 'messages', title: t('personal.messages', '我的消息'), count: query.data?.unreadMessages, rows: query.data?.messages, param: 'id'},
  ];
  return <div className="sp-home-personal">
    {query.isError && <Alert type="error" showIcon title={t('personal.overviewError', '个人工作摘要加载失败')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0,
        text: t('personal.retry', '重试'), onClick: () => void query.refetch()}]}/>} />}
    {cards.map(card => <section className="sp-home-panel" key={card.key} aria-label={card.title}>
      <div className="sp-home-section-heading"><h2>{card.title} <Badge count={card.count} showZero /></h2>
        <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'all', sort: 0, type: 'link', size: 'small',
          text: t('home.viewAll', '查看全部'), onClick: () => navigate(`/my/${card.key}`)}]}/></div>
      {card.key === 'items' && !!query.data?.overdueItems && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{
        key: 'overdue', sort: 0, size: 'small', danger: true, type: 'text', text: `${t('personal.overdue', '逾期事项')}: ${query.data.overdueItems}`,
        onClick: () => navigate('/my/items?view=OVERDUE'),
      }]}/>}
      <Spin spinning={query.isLoading} delay={200}>
        {!query.isError && !query.isLoading && !card.rows?.length && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('personal.nothingPending', '暂无待处理内容')} />}
        <ul className="sp-home-personal-list">{card.rows?.map(row => <li key={row.id}><a href={`#/my/${card.key}?${card.param}=${encodeURIComponent(row.id)}`}
          onClick={event => {if (!event.ctrlKey && !event.metaKey && !event.shiftKey && event.button === 0) {event.preventDefault(); navigate(`/my/${card.key}?${card.param}=${encodeURIComponent(row.id)}`);}}}>{row.title}</a></li>)}</ul>
      </Spin>
    </section>)}
  </div>;
}
