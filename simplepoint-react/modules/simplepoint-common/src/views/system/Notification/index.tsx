import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import TableActions from '@simplepoint/components/Table/Actions';
import type {TableButtonProps} from '@simplepoint/components/Table';
import {post} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, Modal, Typography} from 'antd';
import {useCallback, useMemo, useRef, useState} from 'react';
import {useQueryClient} from '@tanstack/react-query';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {canNotificationAction, notificationDate, notificationFormData, notificationFormUi, usableNotification,
  type NotificationAction, type NotificationRow} from './model';

const {Text} = Typography;
const baseConfig = api['system.notifications'];

type Confirmation = {action: NotificationAction; row: NotificationRow};

const NotificationView = () => {
  const {t} = useI18n();
  const scope = useQueryScope(), client = useQueryClient(), operation = useOperationScope(baseConfig.baseUrl);
  const permissions = useSchema(baseConfig.baseUrl, {staleTime: 30_000, retry: false});
  const [pending, setPending] = useState<Confirmation>(), pendingRef = useRef<Confirmation | undefined>(undefined);
  const [error, setError] = useState<string>(), [notice, setNotice] = useState<string>();
  const [rootLocked, setRootLocked] = useState(false), rootLockRef = useRef(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingRecord, setEditingRecord] = useState<NotificationRow | null>(null);
  const blocked = !!pending || operation.busy || rootLocked || drawerOpen;
  usePageRetention(!blocked, blocked);
  const rootInteraction = useCallback((locked: boolean) => {rootLockRef.current = locked; setRootLocked(locked);}, []);

  const config = useMemo(() => ({
      publish: {
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
      title: string;
      content: string;
      okText: string;
      successMessage: string;
      failureMessage: string;
    }>), [t]);
  const lifecycleAction = useCallback((action: NotificationAction, rows: NotificationRow[]) => {
    if (!operation.current() || !operation.isSelected() || operation.isBusy() || rootLockRef.current || pendingRef.current
      || permissions.error || !permissions.data?.buttons.some(button => button.key === action)
      || rows.length !== 1 || !canNotificationAction(action, rows[0])) return;
    const captured = {action, row: structuredClone(rows[0])}; pendingRef.current = captured; setPending(captured);
    setError(undefined); setNotice(undefined);
  }, [operation.current, operation.isSelected, operation.isBusy, permissions.data, permissions.error]);
  const close = () => {
    if (!operation.current() || !operation.isSelected() || operation.isBusy()) return;
    pendingRef.current = undefined; setPending(undefined); setError(undefined);
  };
  const confirm = async () => {
    const captured = pendingRef.current;
    if (!captured || permissions.error || !permissions.data?.buttons.some(button => button.key === captured.action)
      || !canNotificationAction(captured.action, captured.row)) return;
    const request = operation.begin(); if (!request) return; setError(undefined);
    try {
      const result = await post<NotificationRow>(`${baseConfig.baseUrl}/${encodeURIComponent(captured.row.id)}/${captured.action}`,
        {}, {signal: request.signal, notifyOnError: false});
      if (!operation.current(request.signal) || pendingRef.current !== captured) return;
      pendingRef.current = undefined; setPending(undefined);
      setNotice(config[captured.action].successMessage);
      if (captured.action === 'duplicate' && usableNotification(result) && result.status === 'DRAFT'
        && permissions.data.buttons.some(button => button.key === 'edit')) {
        rootLockRef.current = true; setRootLocked(true);
        setEditingRecord(structuredClone(result)); setDrawerOpen(true);
      }
      // Invalidate the existing scoped controller, never remount the table or reset its query/page.
      void client.invalidateQueries({queryKey: scopedQueryKey(scope, [baseConfig.baseUrl, baseConfig.name])});
    } catch (reason) {if (operation.current(request.signal)) setError(resolveApiErrorMessage(reason, config[captured.action].failureMessage));}
    finally {operation.finish(request);}
  };

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
    if (!operation.selected || pendingRef.current || operation.isBusy() || rootLockRef.current || permissions.error || !permissions.data) return true;
    if (selectedRows.length === 0) return false;
    switch (button.key) {
      case 'edit':
      case 'publish':
        return selectedRows.some((row) => !canNotificationAction('publish', row));
      case 'revoke':
        return selectedRows.some((row) => !canNotificationAction('revoke', row));
      case 'duplicate':
        return selectedRows.some((row) => !canNotificationAction('duplicate', row));
      case 'delete':
      case 'del':
        return selectedRows.some(
          (row) => !usableNotification(row) || row.status !== 'DRAFT' && row.status !== 'REVOKED',
        );
      default:
        return false;
    }
  }, [operation.selected, operation.isBusy, permissions.error, permissions.data, rootLocked, pending]);

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
        '目标类型为租户或用户时，请选择对应的租户或用户。',
      );
      properties.audienceId.minLength = Math.max(1, Number(properties.audienceId.minLength) || 0);
      if (properties.audienceType) next.allOf = [...(next.allOf ?? []), {
        if: {properties: {audienceType: {enum: ['TENANT', 'USER']}}, required: ['audienceType']},
        then: {required: ['audienceId'], properties: {audienceId: {type: 'string', minLength: 1}}},
      }];
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
    publishAt: {width: 180, render: notificationDate},
    expireAt: {width: 180, render: notificationDate},
  }), []);
  const formUiSchema = useCallback((draft: Record<string, unknown>) => {
    const field = permissions.data?.schema.properties?.audienceId;
    const readable = !!field && typeof field === 'object' && !['MASKED', 'HIDDEN'].includes(String(field['x-field-access']));
    return notificationFormUi(draft, readable, readable && typeof field === 'object' && field.readOnly !== true);
  }, [permissions.data?.schema.properties]);

  return (
    <div style={{height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column'}}>
    {notice && <Alert className="notification-operation-notice" type="success" showIcon title={notice} style={{marginBottom: 12}}/>}
    {permissions.error && <Alert className="notification-permission-error" type="error" showIcon title={t('table.schemaLoadFail', '页面结构加载失败，请稍后重试。')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, text: t('table.retry', '重试'),
        disabled: !operation.selected, onClick: () => void permissions.refetch()}]}/>}/>}
    <div style={{flex: 1, minHeight: 0}}><SimpleTable
      {...baseConfig}
      schemaStaleTime={30_000} allowTabEviction={!pending && !operation.busy} confirmDiscard
      onInteractionLockChange={rootInteraction}
      rowSelection={{isRowSelectable: () => !pendingRef.current && !operation.isBusy() && !rootLockRef.current}}
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
      formUiSchema={formUiSchema} formDataTransform={notificationFormData}
      columnOverrides={columnOverrides}
      submitRefreshTargets={{page: true, schema: false}} deleteRefreshTargets={{page: true, schema: false}}
    /></div>
    {pending && operation.selected && <Modal rootClassName="notification-operation-confirm" open destroyOnHidden maskClosable={false}
      closable={!operation.busy} keyboard={!operation.busy} title={config[pending.action].title} onCancel={close}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 0, text: t('action.cancel', '取消'), disabled: operation.busy, onClick: close},
        {key: 'confirm', sort: 1, type: 'primary', danger: pending.action === 'revoke', text: config[pending.action].okText,
          loading: operation.busy, disabled: !!permissions.error || !permissions.data?.buttons.some(button => button.key === pending.action),
          onClick: () => void confirm()},
      ]}/>}>
      <Typography.Paragraph>{config[pending.action].content}</Typography.Paragraph>
      <Typography.Text>{pending.row.title}</Typography.Text>
      {error && <Alert className="notification-operation-error" type="error" showIcon title={error}/>}
    </Modal>}
    </div>
  );
};

export default function NotificationPage() {return <NotificationView key={JSON.stringify(useQueryScope())}/>;}
