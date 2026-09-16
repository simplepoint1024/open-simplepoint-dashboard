import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {TableButtonProps} from '@simplepoint/components/Table';
import {post} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {App as AntdApp, Typography} from 'antd';
import {useCallback, useMemo, useState} from 'react';

const {Text} = Typography;
const baseConfig = api['system.notifications'];

type NotificationRow = {
  id: string;
  title?: string;
  content?: string;
  category?: string;
  priority?: string;
  audienceType?: string;
  audienceId?: string;
  status?: 'DRAFT' | 'PUBLISHED' | 'REVOKED';
  publishAt?: string;
  expireAt?: string;
};

type NotificationAction = 'publish' | 'revoke' | 'duplicate';

const displayDate = (value?: string) => value ? new Date(value).toLocaleString() : '-';

const NotificationView = () => {
  const {message, modal} = AntdApp.useApp();
  const {t} = useI18n();
  const [tableKey, setTableKey] = useState(0);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingRecord, setEditingRecord] = useState<NotificationRow | null>(null);

  const lifecycleAction = useCallback((
    action: NotificationAction,
    rows: NotificationRow[],
  ) => {
    const row = rows[0];
    if (!row?.id) return;
    const config = {
      publish: {
        expectedStatus: 'DRAFT',
        invalidMessage: t('notifications.message.publishDraftOnly', '仅草稿通知可以发布'),
        title: t('notifications.confirm.publish.title', '发布系统通知'),
        content: t(
          'notifications.confirm.publish.content',
          '发布后将立即主动推送给目标用户，且通知内容不可再修改。确定继续吗？',
        ),
        okText: t('notifications.button.publish', '发布'),
        successMessage: t('notifications.message.publishSuccess', '通知已发布'),
        failureMessage: t('notifications.message.publishFailed', '通知发布失败'),
      },
      revoke: {
        expectedStatus: 'PUBLISHED',
        invalidMessage: t('notifications.message.revokePublishedOnly', '仅已发布通知可以撤回'),
        title: t('notifications.confirm.revoke.title', '撤回系统通知'),
        content: t(
          'notifications.confirm.revoke.content',
          '撤回后该通知将从用户的有效收件箱中移除。确定继续吗？',
        ),
        okText: t('notifications.button.revoke', '撤回'),
        successMessage: t('notifications.message.revokeSuccess', '通知已撤回'),
        failureMessage: t('notifications.message.revokeFailed', '通知撤回失败'),
      },
      duplicate: {
        expectedStatus: 'REVOKED',
        invalidMessage: t(
          'notifications.message.duplicateRevokedOnly',
          '仅已撤回通知可以复制为新草稿',
        ),
        title: t('notifications.confirm.duplicate.title', '复制为新草稿'),
        content: t(
          'notifications.confirm.duplicate.content',
          '系统将保留原撤回记录，并创建一个独立的新草稿以供编辑和再次发布。确定继续吗？',
        ),
        okText: t('notifications.button.duplicate', '复制为草稿'),
        successMessage: t(
          'notifications.message.duplicateSuccess',
          '新草稿已创建，请确认内容后重新发布',
        ),
        failureMessage: t('notifications.message.duplicateFailed', '创建新草稿失败'),
      },
    } satisfies Record<NotificationAction, {
      expectedStatus: NotificationRow['status'];
      invalidMessage: string;
      title: string;
      content: string;
      okText: string;
      successMessage: string;
      failureMessage: string;
    }>;
    const current = config[action];
    if (row.status !== current.expectedStatus) {
      message.warning(current.invalidMessage);
      return;
    }
    modal.confirm({
      title: current.title,
      content: current.content,
      okText: current.okText,
      cancelText: t('cancel', '取消'),
      okButtonProps: action === 'revoke' ? {danger: true} : undefined,
      onOk: async () => {
        try {
          const result = await post<NotificationRow>(
            `${baseConfig.baseUrl}/${row.id}/${action}`,
            {},
          );
          if (action === 'duplicate') {
            setEditingRecord(result);
            setDrawerOpen(true);
          }
          message.success(current.successMessage);
          setTableKey((current) => current + 1);
        } catch (error) {
          message.error(resolveApiErrorMessage(
            error,
            current.failureMessage,
          ));
          throw error;
        }
      },
    });
  }, [message, modal, t]);

  const customButtonEvents = useMemo<Record<string, (
    selectedRowKeys: React.Key[],
    selectedRows: NotificationRow[],
    props: TableButtonProps,
  ) => void>>(() => ({
    publish: (_keys, rows) => lifecycleAction('publish', rows),
    revoke: (_keys, rows) => lifecycleAction('revoke', rows),
    duplicate: (_keys, rows) => lifecycleAction('duplicate', rows),
  }), [lifecycleAction]);

  const isButtonDisabled = useCallback((
    button: TableButtonProps,
    _selectedRowKeys: React.Key[],
    selectedRows: NotificationRow[],
  ) => {
    if (selectedRows.length === 0) return false;
    switch (button.key) {
      case 'edit':
      case 'publish':
        return selectedRows.some((row) => row.status !== 'DRAFT');
      case 'revoke':
        return selectedRows.some((row) => row.status !== 'PUBLISHED');
      case 'duplicate':
        return selectedRows.some((row) => row.status !== 'REVOKED');
      case 'delete':
      case 'del':
        return selectedRows.some(
          (row) => row.status !== 'DRAFT' && row.status !== 'REVOKED',
        );
      default:
        return false;
    }
  }, []);

  const formSchemaTransform = useCallback((schema: any) => {
    const next = structuredClone(schema ?? {});
    const properties = next.properties ?? {};
    const readOnlyFields = [
      'status', 'publishAt', 'publishedAt', 'version',
      'createdAt', 'updatedAt', 'createdBy', 'updatedBy', 'deletedAt', 'createOrgDeptId',
    ];
    readOnlyFields.forEach((field) => delete properties[field]);
    if (Array.isArray(next.required)) {
      next.required = next.required.filter((field: string) => !readOnlyFields.includes(field));
    }
    if (properties.category) {
      properties.category.default ??= 'SYSTEM';
    }
    if (properties.priority) {
      properties.priority.default ??= 'NORMAL';
    }
    if (properties.audienceType) {
      properties.audienceType.default ??= 'ALL';
    }
    if (properties.audienceId) {
      properties.audienceId.description = t(
        'notifications.description.audienceId',
        '目标类型为租户或用户时必填，填写对应租户 ID 或用户 ID。',
      );
    }
    return next;
  }, [t]);

  const columnOverrides = useMemo(() => ({
    title: {width: 240, ellipsis: true},
    content: {
      width: 320,
      ellipsis: true,
      render: (value?: string) => <Text ellipsis={{tooltip: value}}>{value || '-'}</Text>,
    },
    category: {width: 120},
    priority: {width: 110},
    audienceType: {width: 120},
    status: {width: 110},
    publishAt: {width: 180, render: displayDate},
    expireAt: {width: 180, render: displayDate},
  }), []);

  return (
    <SimpleTable
      key={tableKey}
      {...baseConfig}
      customButtonEvents={customButtonEvents}
      isButtonDisabled={isButtonDisabled}
      drawerOpen={drawerOpen}
      onDrawerOpenChange={(open) => {
        setDrawerOpen(open);
        if (!open) setEditingRecord(null);
      }}
      editingRecord={editingRecord}
      onEditingRecordChange={setEditingRecord}
      formSchemaTransform={formSchemaTransform}
      columnOverrides={columnOverrides}
    />
  );
};

export default NotificationView;
