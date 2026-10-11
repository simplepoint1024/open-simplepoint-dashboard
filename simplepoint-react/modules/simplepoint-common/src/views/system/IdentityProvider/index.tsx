import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {SimpleTableSubmitAction} from '@simplepoint/components/SimpleTable/types';
import TableActions from '@simplepoint/components/Table/Actions';
import type {TableButtonProps} from '@simplepoint/components/Table';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {post, put} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useOwnedReads} from '@simplepoint/shared/hooks/useOwnedReads';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {Alert, App, Descriptions, Modal, Tag, Typography} from 'antd';
import {useCallback, useMemo, useRef, useState} from 'react';
import {connectionTestResult, providerEditorSchema, providerInput, providerTarget,
  type ConnectionTestResult, type IdentityProviderRow, type ProviderTarget} from './model';

const baseConfig = api['external-identity-providers'];
const testChannel = 'test:';

const IdentityProviderSession = () => {
  const {t} = useI18n(), {message} = App.useApp();
  const operation = useOperationScope(baseConfig.baseUrl), reads = useOwnedReads(operation, true);
  const permissions = useSchema(baseConfig.baseUrl, {staleTime: 30_000, retry: false});
  const granted = useCallback((key: string) => !permissions.error && !!permissions.data?.buttons.some(button => button.key === key),
    [permissions.error, permissions.data]);
  const [probe, setProbe] = useState<ProviderTarget>(), probeRef = useRef<ProviderTarget | undefined>(undefined);
  const [result, setResult] = useState<ConnectionTestResult>();
  const [rootLocked, setRootLocked] = useState(false), rootLockRef = useRef(false);
  const blocked = !!probe || operation.busy || rootLocked;
  usePageRetention(!blocked, blocked);
  const rootInteraction = useCallback((locked: boolean) => {rootLockRef.current = locked; setRootLocked(locked);}, []);

  const runTest = useCallback((captured: ProviderTarget) => {
    if (!operation.current() || !operation.isSelected() || !reads.active || reads.isBusy() || operation.isBusy()
      || rootLockRef.current || probeRef.current !== captured || !granted('test')) return;
    reads.clearErrors(testChannel); setResult(undefined);
    void reads.run(testChannel, async signal => {
      const response = await post<unknown>(`${baseConfig.baseUrl}/${encodeURIComponent(captured.id)}/test`, {},
        {signal, notifyOnError: false});
      const tested = connectionTestResult(response);
      if (!tested) throw new Error(t('external-idp.page.test.invalidResponse', '配置测试返回了无效结果，请重试。'));
      return tested;
    }, tested => {if (probeRef.current === captured) setResult(tested);});
  }, [operation.current, operation.isSelected, operation.isBusy, reads.active, reads.isBusy, reads.clearErrors, reads.run, granted, t]);
  const startTest = useCallback((rows: IdentityProviderRow[]) => {
    if (!operation.current() || !operation.isSelected() || !reads.active || reads.isBusy() || operation.isBusy()
      || rootLockRef.current || probeRef.current || !granted('test') || rows.length !== 1) return;
    const captured = providerTarget(rows[0]); if (!captured) return;
    probeRef.current = captured; setProbe(captured); runTest(captured);
  }, [operation.current, operation.isSelected, operation.isBusy, reads.active, reads.isBusy, granted, runTest]);
  const closeTest = () => {
    if (!operation.current() || !operation.isSelected()) return;
    probeRef.current = undefined; reads.cancel(testChannel); reads.clearErrors(testChannel);
    setProbe(undefined); setResult(undefined);
  };
  const customButtonEvents = useMemo<Record<string, (
    selectedRowKeys: React.Key[], selectedRows: IdentityProviderRow[], props: TableButtonProps,
  ) => void>>(() => ({test: (_keys, rows) => startTest(rows)}), [startTest]);
  const isButtonDisabled = useCallback((button: TableButtonProps, _keys: React.Key[], rows: IdentityProviderRow[]) =>
    !operation.selected || !!probeRef.current || operation.isBusy() || rootLockRef.current || !granted(String(button.key))
      || button.key !== 'add' && rows.some(row => !providerTarget(row)),
  [operation.selected, operation.isBusy, granted, probe, rootLocked]);

  const submit = useCallback(async (action: SimpleTableSubmitAction, values: Record<string, unknown>, current: IdentityProviderRow | null) => {
    const target = providerTarget(current);
    const unavailable = () => new Error(t('table.actionUnavailable', '此操作暂不可用，请刷新页面后重试'));
    if (probeRef.current || reads.isBusy() || !granted(action) || action === 'edit' && !target) throw unavailable();
    const request = operation.begin(); if (!request) throw unavailable();
    try {
      const data = providerInput(permissions.data?.schema, values, action === 'edit');
      const updated = action === 'edit'
        ? await put<IdentityProviderRow>(baseConfig.baseUrl, {...data, id: target!.id}, {signal: request.signal, notifyOnError: false})
        : await post<IdentityProviderRow>(baseConfig.baseUrl, data, {signal: request.signal, notifyOnError: false});
      if (!operation.current(request.signal)) return undefined;
      if (operation.isSelected()) message.success(action === 'edit' ? t('table.editSuccess', '修改成功') : t('table.addSuccess', '新增成功'));
      return updated;
    } catch (error) {if (operation.current(request.signal)) throw error; return undefined;}
    finally {operation.finish(request);}
  }, [reads.isBusy, granted, operation.begin, operation.current, operation.isSelected, operation.finish, permissions.data?.schema, message, t]);
  const formSchemaTransform = useCallback((schema: any, record: IdentityProviderRow | null) => providerEditorSchema(schema, record,
    t('external-idp.page.form.secret.edit', '留空表示保持当前加密密钥不变')), [t]);
  const columnOverrides = useMemo(() => {
    const booleanTag = (value: unknown) => <Tag color={value === true ? 'green' : 'default'}>
      {value === true ? t('common.yes', '是') : value === false ? t('common.no', '否') : '—'}</Tag>;
    return {clientSecretConfigured: {width: 110, render: booleanTag}, enabled: {width: 90, render: booleanTag}, sortOrder: {width: 100}};
  }, [t]);
  const testing = reads.loading.includes(testChannel), testError = reads.errors[testChannel];
  const failed = t('external-idp.page.test.failedTitle', '配置测试失败');

  return <div style={{height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column'}}>
    <Alert type="info" closable showIcon style={{marginBottom: 16, flexShrink: 0}}
      title={t('external-idp.entity.title', '外部身份提供商')}
      description={t('external-idp.page.callbackInstruction', '测试配置后，使用服务端返回的回调地址在外部平台登记；回调地址必须与授权服务公开地址一致。')}/>
    <Alert type="warning" showIcon style={{marginBottom: 16, flexShrink: 0}}
      title={t('external-idp.page.github.title', 'GitHub 接入检查')}
      description={t('external-idp.page.github.configurationDescription', 'GitHub OAuth App 必须填写与授权服务完全一致的 Authorization callback URL；授权范围保留 read:user,user:email。配置测试只校验端点与格式，Client ID/Secret 需要通过一次真实登录确认。')}/>
    <div style={{flex: 1, minHeight: 0}}><SimpleTable {...baseConfig} schemaStaleTime={30_000}
      allowTabEviction={!probe && !operation.busy} confirmDiscard onInteractionLockChange={rootInteraction}
      rowSelection={{isRowSelectable: () => !probeRef.current && !operation.isBusy() && !rootLockRef.current}}
      customButtonEvents={customButtonEvents} isButtonDisabled={isButtonDisabled} onSubmit={submit}
      formSchemaTransform={formSchemaTransform} columnOverrides={columnOverrides}
      submitRefreshTargets={{page: true, schema: false}} deleteRefreshTargets={{page: true, schema: false}}/></div>
    {probe && operation.selected && reads.active && <Modal rootClassName="identity-provider-test" open destroyOnHidden maskClosable={false}
      title={t('external-idp.button.test', '测试配置')} onCancel={closeTest}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'close', sort: 0, text: t('external-idp.page.test.close', '关闭'), onClick: closeTest},
        {key: 'retry', sort: 1, type: 'primary', text: result ? t('external-idp.page.test.again', '重新测试') : t('table.retry', '重试'),
          loading: testing, disabled: !granted('test'), onClick: () => runTest(probe)},
      ]}/>}>
      <Typography.Paragraph>{probe.label || probe.registrationId || probe.id}</Typography.Paragraph>
      <Alert type="info" showIcon title={t('external-idp.page.test.noLogin', '此测试不会验证客户端凭据或完成真实登录，也不会修改配置。')}/>
      {testing ? <Alert className="identity-provider-test-loading" type="info" showIcon style={{marginTop: 12}}
        title={t('external-idp.page.test.loading', '正在测试配置…')}/>
        : testError ? <Alert className="identity-provider-test-error" type="error" showIcon style={{marginTop: 12}}
          title={resolveApiErrorMessage(testError, failed)}/>
          : result ? <>
            <Alert className="identity-provider-test-result" type={result.success ? 'success' : 'error'} showIcon style={{marginTop: 12}}
              title={result.success ? t('external-idp.page.test.success', '配置有效') : failed} description={result.message}/>
            <Descriptions column={1} size="small" style={{marginTop: 16}}>
              <Descriptions.Item label="Authorization URI">{result.authorizationUri || '—'}</Descriptions.Item>
              <Descriptions.Item label="Token URI">{result.tokenUri || '—'}</Descriptions.Item>
              <Descriptions.Item label="UserInfo URI">{result.userInfoUri || '—'}</Descriptions.Item>
              <Descriptions.Item label="JWK Set URI">{result.jwkSetUri || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('external-idp.page.test.callback', '回调地址')}>{result.callbackUri || '—'}</Descriptions.Item>
            </Descriptions>
            {result.callbackUri?.startsWith('/') && !result.callbackUri.startsWith('//') && <Alert type="warning" showIcon
              title={t('external-idp.page.test.relativeCallback', '服务端仅返回了相对回调路径。请先配置授权服务公开地址，再测试取得完整回调地址。')}/>}
          </> : <Alert className="identity-provider-test-interrupted" type="warning" showIcon style={{marginTop: 12}}
            title={t('external-idp.page.test.interrupted', '配置测试已中断，请重试。')}/>}
    </Modal>}
  </div>;
};

export default function IdentityProviderPage() {return <IdentityProviderSession key={JSON.stringify(useQueryScope())}/>;}
