import {useState} from 'react';
import {Badge, Button, Drawer, Pagination, Space, Tooltip} from 'antd';
import {BellOutlined} from '@simplepoint/shared/types/icon';
import {useI18n} from '@/layouts/i18n/useI18n';
import {useNotificationInbox, useNotifications} from '@/notifications/NotificationProvider';
import {NotificationList} from '@/notifications/NotificationList';

export function NotificationButton() {
  const {t} = useI18n();
  const {unread, open, setOpen, busy, markRead} = useNotifications();
  const [page, setPage] = useState(0);
  const inbox = useNotificationInbox(open, page);
  return <>
    <Tooltip title={t('nav.notifications', '通知')}>
      <Badge count={unread} overflowCount={99} size="small" offset={[-2, 2]}>
        <Button type="text" icon={<BellOutlined />} aria-label={t('nav.notifications', '通知')} onClick={() => {setPage(0); setOpen(true);}} />
      </Badge>
    </Tooltip>
    <Drawer title={t('nav.notifications', '通知中心')} open={open} onClose={() => setOpen(false)} size={440}
      extra={<Button size="small" disabled={!unread} loading={busy} onClick={() => void markRead()}>{t('nav.markAllRead', '全部已读')}</Button>}
      footer={<Space style={{width: '100%', justifyContent: 'center'}}><Pagination size="small" current={page + 1} pageSize={20}
        total={inbox.data?.page.totalElements ?? 0} showSizeChanger={false} onChange={value => setPage(value - 1)} /></Space>}>
      <NotificationList items={inbox.data?.content ?? []} loading={inbox.isFetching} error={inbox.isError}
        onRetry={() => void inbox.refetch()} active={open} />
    </Drawer>
  </>;
}
