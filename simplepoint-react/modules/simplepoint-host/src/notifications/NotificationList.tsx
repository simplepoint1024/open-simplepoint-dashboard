import {useState} from 'react';
import {Alert, Badge, Button, Empty, Modal, Spin, Tag, Typography} from 'antd';
import {useNavigate} from 'react-router';
import {useI18n} from '@/layouts/i18n/useI18n';
import {useNotifications} from './NotificationProvider';
import {notificationTarget, type InboxNotification} from './models';
import './notifications.css';

export function NotificationList({items, loading, error, onRetry, active = true}: {
  items: InboxNotification[]; loading: boolean; error: boolean; onRetry: () => void; active?: boolean;
}) {
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
      action={<Button size="small" onClick={onRetry}>{t('action.retry', '重试')}</Button>} />}
    <Spin spinning={loading} delay={200}>
      {!items.length && !loading && !error && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('nav.notification.empty', '暂无通知')} />}
      <ul className="sp-notification-list">
        {items.map(item => <li key={item.id}>
          <button className={`sp-notification-item${item.read ? '' : ' is-unread'}`} onClick={() => {
            setSelected(item);
            if (!item.read && !busy) void markRead(item.id);
          }}>
            <div className="sp-notification-title"><Badge status={item.read ? 'default' : 'processing'} />
              <strong>{item.title}</strong>
              <Tag color={item.category === 'SECURITY' ? 'red' : 'blue'}>{t(`notifications.category.${item.category}`, item.category)}</Tag>
              {(item.priority === 'HIGH' || item.priority === 'URGENT') && <Tag color="orange">{t(`notifications.priority.${item.priority}`, item.priority)}</Tag>}
            </div>
            <p>{item.content}</p>
            <time dateTime={item.publishAt}>{date(item.publishAt)}</time>
          </button>
        </li>)}
      </ul>
    </Spin>
    <Modal destroyOnHidden title={selected?.title} open={active && Boolean(selected)} onCancel={() => setSelected(null)}
      footer={<><Button onClick={() => setSelected(null)}>{t('home.close', '关闭')}</Button>
        {target && <Button type="primary" onClick={() => {
          if (target.external) window.open(target.url, '_blank', 'noopener,noreferrer');
          else navigate(target.url);
          setSelected(null); setOpen(false);
        }}>{t('home.notifications.openLink', '查看关联内容')}</Button>}</>}>
      <Typography.Paragraph className="sp-notification-detail">{selected?.content}</Typography.Paragraph>
      <Typography.Text type="secondary">{selected && date(selected.publishAt)}</Typography.Text>
    </Modal>
  </>;
}
