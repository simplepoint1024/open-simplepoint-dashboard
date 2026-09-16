import api from '@/api/index';
import {
  assignPlatformIdentity,
  createAccount,
  fetchCapabilities,
  PlatformAccount,
  PlatformAccountCommand,
  updateAccount,
} from '@/api/system/platform-account';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {TableButtonProps} from '@simplepoint/components/Table';
import {useData} from '@simplepoint/shared/api/methods';
import {EMPTY_QUERY_SCOPE, getQueryScopeSnapshot, subscribeQueryScope} from '@simplepoint/shared/api/queryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, App, Form, Input, Modal, Select, Space, Switch, Tag} from 'antd';
import {useCallback, useMemo, useState, useSyncExternalStore} from 'react';

const baseConfig = api['platform.accounts'];

export default function PlatformAccounts() {
  const scope = useSyncExternalStore(subscribeQueryScope, getQueryScopeSnapshot, () => EMPTY_QUERY_SCOPE);
  return <PlatformAccountsContent key={scope} scope={scope}/>;
}

function PlatformAccountsContent({scope}: {scope: string}) {
  const {t} = useI18n();
  const {message} = App.useApp();
  const [mode, setMode] = useState<'create' | 'edit' | 'identity'>();
  const [selected, setSelected] = useState<PlatformAccount>();
  const [saving, setSaving] = useState(false);
  const [tableRevision, setTableRevision] = useState(0);
  const [form] = Form.useForm<PlatformAccountCommand>();
  const capabilities = useData(['platformAccountCapabilities', scope], fetchCapabilities);

  const open = useCallback((next: 'create' | 'edit' | 'identity', account?: PlatformAccount) => {
    form.resetFields();
    setSelected(account);
    setMode(next);
    form.setFieldsValue(next === 'identity'
      ? {roles: account?.roles ?? [], superAdmin: account?.superAdmin ?? false}
      : next === 'edit' ? {name: account?.name, enabled: account?.enabled} : {});
  }, [form]);

  const customButtonEvents = useMemo<Record<string, (
    selectedRowKeys: React.Key[],
    selectedRows: PlatformAccount[],
    props: TableButtonProps,
  ) => void>>(() => ({
    add: () => open('create'),
    edit: (_keys, rows) => open('edit', rows[0]),
    'config.identity': (_keys, rows) => open('identity', rows[0]),
  }), [open]);

  const columnOverrides = useMemo(() => ({
    name: {width: 180},
    email: {width: 240},
    enabled: {width: 120},
    roles: {
      width: 300,
      sorter: false,
      filterDropdown: undefined,
      render: (_value: PlatformAccount['roles'], row: PlatformAccount) => (
        <Space wrap size={[4, 4]}>
          {row.superAdmin && <Tag color="red">{t('platform-accounts.root', '超级管理员')}</Tag>}
          {row.roles.map(role => <Tag key={role}>{role}</Tag>)}
          {!row.superAdmin && row.roles.length === 0 ? '-' : null}
        </Space>
      ),
    },
  }), [t]);

  const save = async () => {
    if (scope !== getQueryScopeSnapshot()) return;
    let values: PlatformAccountCommand;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    if (scope !== getQueryScopeSnapshot()) return;
    setSaving(true);
    try {
      if (mode === 'create') {
        await createAccount(values);
      } else if (selected) {
        const command = {...values, revision: selected.revision};
        if (mode === 'identity') await assignPlatformIdentity(selected.id, command);
        else await updateAccount(selected.id, command);
      }
      setMode(undefined);
      setSelected(undefined);
      form.resetFields();
      setTableRevision(value => value + 1);
      await capabilities.refetch();
      message.success(t('platform-accounts.saved', '已保存，权限变更将在后续请求生效'));
    } catch {
      form.setFieldsValue({confirmationPassword: '', confirmationCode: ''});
      message.error(t('platform-accounts.failed', '操作失败，请检查权限、身份验证或刷新后的账号版本'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div style={{height: '100%', display: 'flex', flexDirection: 'column', gap: 16}}>
      <Alert
        type="info"
        showIcon
        message={t('platform-accounts.boundary', '这里管理全局账号；平台角色不自动获得租户业务数据权限。管理员身份只能通过独立授权操作修改。')}
      />
      {capabilities.error ? (
        <Alert type="error" showIcon message={t('platform-accounts.loadFailed', '平台账号加载失败，请确认已切换至平台工作台且拥有权限')}/>
      ) : null}
      <div style={{flex: 1, minHeight: 0}}>
        <SimpleTable
          key={tableRevision}
          {...baseConfig}
          customButtonEvents={customButtonEvents}
          columnOverrides={columnOverrides}
          isButtonDisabled={() => !capabilities.data || !!capabilities.error}
        />
      </div>
      <Modal
        open={!!mode}
        title={mode === 'identity'
          ? t('platform-accounts.assign', '授予/撤销平台身份')
          : t('platform-accounts.account', '平台账号')}
        onOk={() => void save()}
        confirmLoading={saving}
        maskClosable={false}
        destroyOnHidden
        onCancel={() => {
          if (!saving) {
            setMode(undefined);
            setSelected(undefined);
            form.resetFields();
          }
        }}
      >
        <Form form={form} layout="vertical" preserve={false} disabled={saving}>
          {mode !== 'identity' ? (
            <Form.Item name="name" label={t('platform-accounts.name', '姓名')} rules={[{max: 100}]}>
              <Input/>
            </Form.Item>
          ) : null}
          {mode === 'create' ? (
            <>
              <Form.Item name="email" label={t('platform-accounts.email', '邮箱')} rules={[{required: true, type: 'email'}]}>
                <Input/>
              </Form.Item>
              <Form.Item name="initialPassword" label={t('platform-accounts.initialPassword', '新账号初始密码')} rules={[{required: true, min: 12, max: 64}]}>
                <Input.Password autoComplete="new-password"/>
              </Form.Item>
            </>
          ) : null}
          {mode === 'edit' ? (
            <Form.Item name="enabled" label={t('platform-accounts.enabled', '账号启用')} valuePropName="checked">
              <Switch/>
            </Form.Item>
          ) : null}
          {mode === 'identity' ? (
            <>
              <Alert type="warning" message={t('platform-accounts.rootWarning', '超级管理员拥有应急最高权限；普通平台管理员仅管理账号与平台账号审计。不能撤销最后一个有效超级管理员。')}/>
              <Form.Item name="roles" label={t('platform-accounts.roles', '平台角色')}>
                <Select
                  mode="multiple"
                  options={['PLATFORM_ADMIN', 'ACCOUNT_ADMIN', 'AUDITOR'].map(role => ({label: role, value: role}))}
                />
              </Form.Item>
              <Form.Item name="superAdmin" label={t('platform-accounts.root', '超级管理员')} valuePropName="checked">
                <Switch/>
              </Form.Item>
            </>
          ) : null}
          <Form.Item name="reason" label={t('platform-accounts.reason', '操作原因')} rules={[{required: true, whitespace: true, max: 500}]}>
            <Input.TextArea/>
          </Form.Item>
          <Form.Item name="confirmationPassword" label={t('platform-accounts.confirmPassword', '你的当前账号密码（二次验证）')} rules={[{required: true}]}>
            <Input.Password autoComplete="current-password"/>
          </Form.Item>
          {capabilities.data?.twoFactorEnabled ? (
            <Form.Item name="confirmationCode" label={t('platform-accounts.confirmCode', '你的动态验证码')} rules={[{required: true, pattern: /^[0-9]{6}$/}]}>
              <Input autoComplete="one-time-code" maxLength={6}/>
            </Form.Item>
          ) : null}
        </Form>
      </Modal>
    </div>
  );
}
