import {useState} from 'react';
import {Alert, Badge, Empty, Modal, Spin, Tag, Typography} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useNavigate} from 'react-router';
import {useI18n} from '@/layouts/i18n/useI18n';
import {useNotifications} from './NotificationProvider';
import {notificationTarget, type InboxNotification} from './models';
import './notifications.css';

type Props = {
  items: InboxNotification[]; loading: boolean; error: boolean; onRetry: () => void; active?: boolean;
};
export function NotificationList(props: Props) {
  const scope = useQueryScope();
  return <NotificationListSession key={JSON.stringify(scope)} {...props}/>;
}
function NotificationListSession({items, loading, error, onRetry, active = true}: Props) {
  const {t, locale} = useI18n();
  const {markRead, busy, setOpen} = useNotifications();
  const [selected, setSelected] = useState<InboxNotification | null>(null);
  const navigate = useNavigate();
  const target = notificationTarget(selected?.linkUrl);
  const date = (value: string) => {
    const time = new Date(value);
    return Number.isNaN(time.getTime()) ? '—' : new Intl.DateTimeFormat(locale, {dateStyle: 'medium', timeStyle: 'short'}).format(time);
  };
  return <>
    {error && <Alert type="error" showIcon title={t('home.notifications.error', '通知加载失败，请重试')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, size: 'small',
        text: t('action.retry', '重试'), onClick: onRetry}]}/>} />}
    <Spin spinning={loading} delay={200}>
      {!items.length && !loading && !error && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('nav.notification.empty', '暂无通知')} />}
      <ul className="sp-notification-list">
        {items.map(item => <li key={item.id}>
          <TableActions selectedKeys={[item.id]} selectedRows={[item]} buttons={[{key: 'read', sort: 0, type: 'text', text: item.title,
            className: `sp-notification-item${item.read ? '' : ' is-unread'}`, onClick: () => {
            setSelected(item);
            if (!item.read && !busy) void markRead(item.id);
          }, children: <>
            <span className="sp-notification-title"><Badge status={item.read ? 'default' : 'processing'} />
              <strong>{item.title}</strong>
              <Tag color={item.category === 'SECURITY' ? 'red' : 'blue'}>{t(`notifications.category.${item.category}`, item.category)}</Tag>
              {(item.priority === 'HIGH' || item.priority === 'URGENT') && <Tag color="orange">{t(`notifications.priority.${item.priority}`, item.priority)}</Tag>}
            </span>
            <span className="sp-notification-content">{item.content}</span>
            <time dateTime={item.publishAt}>{date(item.publishAt)}</time>
          </>}]}/>
        </li>)}
      </ul>
    </Spin>
    <Modal destroyOnHidden title={selected?.title} open={active && Boolean(selected)} onCancel={() => setSelected(null)}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'close', sort: 0, text: t('home.close', '关闭'), onClick: () => setSelected(null)},
        ...(target ? [{key: 'target', sort: 1, type: 'primary' as const, text: t('home.notifications.openLink', '查看关联内容'), onClick: () => {
          if (target.external) window.open(target.url, '_blank', 'noopener,noreferrer');
          else navigate(target.url);
          setSelected(null); setOpen(false);
        }}] : []),
      ]}/> }>
      <Typography.Paragraph className="sp-notification-detail">{selected?.content}</Typography.Paragraph>
      <Typography.Text type="secondary">{selected && date(selected.publishAt)}</Typography.Text>
    </Modal>
  </>;
}
