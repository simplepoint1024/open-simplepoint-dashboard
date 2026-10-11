import api from '@/api';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {get, useData} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import PageSection from '@simplepoint/shared/page/Section';
import {Alert, Spin, Tabs} from 'antd';
import {useEffect, useMemo, useState} from 'react';
import TableActions from '@simplepoint/components/Table/Actions';
import RuntimeNodes from './RuntimeNodes';
import RuntimePools from './RuntimePools';
import RuntimeSecrets from './RuntimeSecrets';
import RuntimeWorkloads from './RuntimeWorkloads';

export type RuntimePermissions = {
  viewRuntime: boolean;
  viewNodes: boolean;
  managePools: boolean;
  manageWorkloads: boolean;
  manageSecrets: boolean;
};

type RuntimeProps = {
  permissions?: RuntimePermissions;
};

const Runtime = ({permissions: providedPermissions}: RuntimeProps = {}) => {
  const config = api['ai-workbench.mcp-servers'];
  const {t, ensure, locale} = useI18n();
  const [activeTab, setActiveTab] = useState('pools');
  const permissionsQuery = useData(['mcp', 'runtime-permissions', config.baseUrl], ({signal}) =>
    get<RuntimePermissions>(`${config.baseUrl}/workbench-permissions`, undefined, {signal, notifyOnError: false}),
    {enabled: !providedPermissions, retry: false});
  const permissions = providedPermissions ?? permissionsQuery.data;
  const permissionsLoading = permissionsQuery.isFetching;
  const permissionsError = permissionsQuery.error ? resolveApiErrorMessage(permissionsQuery.error,
    t('ai.mcp.center.error.permissions', 'MCP 中心权限加载失败')) : undefined;

  useEffect(() => {
    void ensure(config.i18nNamespaces);
  }, [config.i18nNamespaces, ensure, locale]);


  const canViewPools = Boolean(permissions?.viewRuntime || permissions?.managePools);
  const canViewWorkloads = Boolean(permissions?.viewRuntime || permissions?.manageWorkloads);
  const canViewSecrets = Boolean(
    permissions?.viewRuntime
    || permissions?.manageSecrets
    || permissions?.managePools
    || permissions?.manageWorkloads,
  );

  const items = useMemo(() => [
    canViewPools ? {
      key: 'pools',
      label: t('ai.runtime.tab.pools', 'Runtime Pools'),
      children: (
        <PageSection selected={activeTab === 'pools'}><div style={{height: '100%', overflow: 'auto'}}>
          <RuntimePools
            active={activeTab === 'pools'}
            poolsUrl={config.poolsUrl}
            secretsUrl={config.secretsUrl}
            serversUrl={config.baseUrl}
            canManage={Boolean(permissions?.managePools)}
          />
        </div></PageSection>
      ),
    } : null,
    canViewWorkloads ? {
      key: 'workloads',
      label: t('ai.runtime.tab.workloads', 'Workloads'),
      children: (
        <PageSection selected={activeTab === 'workloads'}><div style={{height: '100%', overflow: 'auto'}}>
          <RuntimeWorkloads
            active={activeTab === 'workloads'}
            workloadsUrl={config.workloadsUrl}
            secretsUrl={config.secretsUrl}
            serversUrl={config.baseUrl}
            canManage={Boolean(permissions?.manageWorkloads)}
          />
        </div></PageSection>
      ),
    } : null,
    permissions?.viewNodes ? {
      key: 'nodes',
      label: t('ai.runtime.tab.nodes', 'Nodes（平台）'),
      children: (
        <PageSection selected={activeTab === 'nodes'}><div style={{height: '100%', overflow: 'auto'}}>
          <RuntimeNodes active={activeTab === 'nodes'} nodesUrl={config.nodesUrl}/>
        </div></PageSection>
      ),
    } : null,
    canViewSecrets ? {
      key: 'secrets',
      label: t('ai.runtime.tab.secrets', 'Secrets'),
      children: (
        <PageSection selected={activeTab === 'secrets'}><div style={{height: '100%', overflow: 'auto'}}>
          <RuntimeSecrets
            active={activeTab === 'secrets'}
            secretsUrl={config.secretsUrl}
            canManage={Boolean(permissions?.manageSecrets)}
          />
        </div></PageSection>
      ),
    } : null,
  ].filter((item): item is NonNullable<typeof item> => item !== null), [
    activeTab,
    canViewPools,
    canViewSecrets,
    canViewWorkloads,
    config.nodesUrl,
    config.poolsUrl,
    config.secretsUrl,
    config.baseUrl,
    config.workloadsUrl,
    permissions?.managePools,
    permissions?.manageSecrets,
    permissions?.manageWorkloads,
    permissions?.viewNodes,
    t,
  ]);

  useEffect(() => {
    if (!items.some((item) => item.key === activeTab)) {
      setActiveTab(items[0]?.key ?? '');
    }
  }, [activeTab, items]);

  if (!permissions) {
    return permissionsError ? (
      <Alert
        showIcon
        type="error"
        message={t('ai.mcp.center.error.permissions', 'MCP 中心权限加载失败')}
        description={permissionsError}
        action={(
          <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
            {key: 'retry', sort: 1, text: t('ai.mcp.action.retry', '重试'), size: 'small'},
          ]} onButtonEvents={{retry: () => void permissionsQuery.refetch()}}/>
        )}
      />
    ) : <Spin spinning={permissionsLoading} fullscreen={false}/>;
  }

  return (
    <div style={{
      height: '100%',
      minHeight: 0,
      display: 'flex',
      flexDirection: 'column',
      overflow: 'hidden',
    }}>
      <Alert
        showIcon
        type="info"
        closable
        style={{marginBottom: 16, flexShrink: 0}}
        message={t('ai.runtime.title', 'OCI MCP 运行时管理')}
        description={t(
          'ai.runtime.description',
          '管理托管 MCP Server 的声明式副本、实际 Workload、执行节点和加密 Secret；作用域由当前平台或租户上下文自动确定。',
        )}
      />
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        style={{flex: 1, minHeight: 0}}
        styles={{
          body: {minHeight: 0, overflow: 'hidden'},
          content: {height: '100%'},
        }}
        items={items}
      />
    </div>
  );
};

export default function ScopedRuntime(props: RuntimeProps) {
  const scope = useQueryScope();
  return <Runtime key={JSON.stringify(scope)} {...props}/>;
}
