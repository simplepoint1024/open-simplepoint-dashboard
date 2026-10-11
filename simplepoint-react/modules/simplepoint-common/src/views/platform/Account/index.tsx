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
import SForm, {type SFormHandle} from '@simplepoint/components/SForm';
import type {TableButtonProps} from '@simplepoint/components/Table';
import {useData} from '@simplepoint/shared/api/methods';
import {EMPTY_QUERY_SCOPE, getQueryScopeSnapshot, subscribeQueryScope} from '@simplepoint/shared/api/queryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, App, Modal, Space, Tag} from 'antd';
import {useCallback, useMemo, useRef, useState, useSyncExternalStore, type ComponentProps} from 'react';
import {accountActionDisabled} from './accountActions';

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
  const savingRef = useRef(false);
  const [tableRevision, setTableRevision] = useState(0);
  const formRef = useRef<SFormHandle>(null);
  const [formData, setFormData] = useState<Partial<PlatformAccountCommand>>({});
  const capabilities = useData(['platformAccountCapabilities', scope], fetchCapabilities);

  const open = useCallback((next: 'create' | 'edit' | 'identity', account?: PlatformAccount) => {
    setSelected(account);
    setMode(next);
    setFormData(next === 'identity'
      ? {roles: account?.roles ?? [], superAdmin: account?.superAdmin ?? false}
      : next === 'edit' ? {name: account?.name, enabled: account?.enabled} : {});
  }, []);

  const schema = useMemo<ComponentProps<typeof SForm>['schema']>(() => ({type: 'object',
    required: ['reason', 'confirmationPassword', ...(mode === 'create' ? ['email', 'initialPassword'] : []),
      ...(capabilities.data?.twoFactorEnabled ? ['confirmationCode'] : [])],
    properties: {
      ...(mode !== 'identity' ? {name: {type: 'string', title: t('platform-accounts.name', '姓名'), maxLength: 100}} : {}),
      ...(mode === 'create' ? {
        email: {type: 'string', title: t('platform-accounts.email', '邮箱'), format: 'email'},
        initialPassword: {type: 'string', title: t('platform-accounts.initialPassword', '新账号初始密码'), minLength: 12, maxLength: 64},
      } : {}),
      ...(mode === 'edit' ? {enabled: {type: 'boolean', title: t('platform-accounts.enabled', '账号启用')}} : {}),
      ...(mode === 'identity' ? {
        roles: {type: 'array', title: t('platform-accounts.roles', '平台角色'), uniqueItems: true,
          items: {type: 'string', enum: ['PLATFORM_ADMIN', 'ACCOUNT_ADMIN', 'AUDITOR']}},
        superAdmin: {type: 'boolean', title: t('platform-accounts.root', '超级管理员')},
      } : {}),
      reason: {type: 'string', title: t('platform-accounts.reason', '操作原因'), pattern: '\\S', maxLength: 500},
      confirmationPassword: {type: 'string', title: t('platform-accounts.confirmPassword', '你的当前账号密码（二次验证）'), minLength: 1},
      ...(capabilities.data?.twoFactorEnabled ? {confirmationCode: {type: 'string',
        title: t('platform-accounts.confirmCode', '你的动态验证码'), pattern: '^[0-9]{6}$', maxLength: 6}} : {}),
    },
  }), [capabilities.data?.twoFactorEnabled, mode, t]);

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
          {row.superAdmin === true && <Tag color="red">{t('platform-accounts.root', '超级管理员')}</Tag>}
          {Array.isArray(row.roles) ? row.roles.map(role => <Tag key={role}>{role}</Tag>) : row.roles ?? '-'}
          {row.superAdmin !== true && Array.isArray(row.roles) && row.roles.length === 0 ? '-' : null}
        </Space>
      ),
    },
  }), [t]);

  const save = async (values: PlatformAccountCommand) => {
    if (scope !== getQueryScopeSnapshot() || savingRef.current || !mode
      || accountActionDisabled(mode === 'create' ? 'add' : mode === 'identity' ? 'config.identity' : 'edit',
        selected ? [selected] : [], capabilities.data?.permissions)) return;
    savingRef.current = true; setSaving(true);
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
      setFormData({});
      setTableRevision(value => value + 1);
      await capabilities.refetch();
      message.success(t('platform-accounts.saved', '已保存，权限变更将在后续请求生效'));
    } catch {
      setFormData(current => ({...current, confirmationPassword: '', confirmationCode: ''}));
      message.error(t('platform-accounts.failed', '操作失败，请检查权限、身份验证或刷新后的账号版本'));
    } finally {
      savingRef.current = false; setSaving(false);
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
          isButtonDisabled={(button, _keys, rows) => !!capabilities.error
            || accountActionDisabled(button.key, rows, capabilities.data?.permissions)}
        />
      </div>
      <Modal
        open={!!mode}
        title={mode === 'identity'
          ? t('platform-accounts.assign', '授予/撤销平台身份')
          : t('platform-accounts.account', '平台账号')}
        onOk={() => formRef.current?.submit()}
        confirmLoading={saving}
        maskClosable={false}
        destroyOnHidden
        onCancel={() => {
          if (!saving) {
            setMode(undefined);
            setSelected(undefined);
            setFormData({});
          }
        }}
      >
        {mode === 'identity' && <Alert type="warning" message={t('platform-accounts.rootWarning', '超级管理员拥有应急最高权限；普通平台管理员仅管理账号与平台账号审计。不能撤销最后一个有效超级管理员。')}/>}
        {mode && <SForm key={`${mode}:${selected?.id ?? 'new'}`} ref={formRef} hideSubmit schema={schema}
          uiSchema={{reason: {'ui:widget': 'textarea'}, roles: {'ui:widget': 'select'},
            initialPassword: {'ui:widget': 'password', 'ui:options': {autoComplete: 'new-password'}},
            confirmationPassword: {'ui:widget': 'password', 'ui:options': {autoComplete: 'current-password'}},
            confirmationCode: {'ui:options': {autoComplete: 'one-time-code'}}}}
          formData={formData} disabled={saving}
          onChange={event => setFormData(event.formData ?? {})}
          onSubmit={event => void save(event.formData as PlatformAccountCommand)}/>}
      </Modal>
    </div>
  );
}
