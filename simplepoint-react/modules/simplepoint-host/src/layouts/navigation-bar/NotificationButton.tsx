import {useState} from 'react';
import {useNavigate} from 'react-router';
import TableActions from '@simplepoint/components/Table/Actions';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {Alert, Badge, Button, Divider, Drawer, Pagination, Space, Tooltip} from 'antd';
import {BellOutlined} from '@simplepoint/shared/types/icon';
import {useI18n} from '@/layouts/i18n/useI18n';
import {useNotificationInbox, useNotifications, usePersonalMessageSummary} from '@/notifications/NotificationProvider';
import {NotificationList} from '@/notifications/NotificationList';

export function NotificationButton() {
  const scope = useQueryScope();
  return <NotificationButtonSession key={JSON.stringify(scope)}/>;
}
function NotificationButtonSession() {
  const {t} = useI18n();
  const navigate = useNavigate();
  const {unread, open, setOpen, busy, markRead} = useNotifications();
  const [page, setPage] = useState(0);
  const inbox = useNotificationInbox(open, page);
  const personal = usePersonalMessageSummary();
  const personalUnread = Math.max(0, Number(personal.data?.unreadMessages) || 0);
  return <>
    <Tooltip title={t('nav.notificationsAndMessages', '通知与消息')}>
      <Badge count={unread + personalUnread} overflowCount={99} size="small" offset={[-2, 2]}>
        <Button type="text" icon={<BellOutlined />} aria-label={t('nav.notificationsAndMessages', '通知与消息')} onClick={() => {setPage(0); setOpen(true);}} />
      </Badge>
    </Tooltip>
    <Drawer title={t('nav.notificationsAndMessages', '通知与消息')} open={open} onClose={() => setOpen(false)} size={440}
      extra={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'readAll', sort: 0, size: 'small',
        disabled: !unread || busy, loading: busy, text: t('nav.markSystemRead', '系统通知全部已读'), onClick: () => void markRead()}]}/>}
      footer={<Space style={{width: '100%', justifyContent: 'center'}}><Pagination size="small" current={page + 1} pageSize={20}
        total={inbox.data?.page.totalElements ?? 0} showSizeChanger={false} onChange={value => setPage(value - 1)} /></Space>}>
      <section aria-label={t('personal.messages', '我的消息')}>
        {personal.isError && <Alert type="warning" showIcon title={t('nav.personalMessagesUnavailable', '个人消息数量暂时无法加载')}
          action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0,
            text: t('personal.retry', '重试'), onClick: () => void personal.refetch()}]}/>}/>}
        <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'messages', sort: 0, type: 'link',
          text: `${t('personal.messages', '我的消息')}${!personal.isError && personal.data ? ` (${personalUnread})` : ''}`,
          onClick: () => {setOpen(false); navigate('/my/messages');}}]}/>
        <p>{t('nav.personalMessagesHint', '工作流提醒和审批结果统一在我的消息中查看。')}</p>
      </section>
      <Divider>{t('nav.systemNotifications', '系统通知')}</Divider>
      <NotificationList items={inbox.data?.content ?? []} loading={inbox.isFetching} error={inbox.isError}
        onRetry={() => void inbox.refetch()} active={open} />
    </Drawer>
  </>;
}
