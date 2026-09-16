import api from '@/api';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, Button, Spin, Tabs} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';
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
  const [loadedPermissions, setLoadedPermissions] = useState<RuntimePermissions>();
  const [permissionsError, setPermissionsError] = useState<string>();
  const [permissionsLoading, setPermissionsLoading] = useState(!providedPermissions);
  const permissions = providedPermissions ?? loadedPermissions;

  useEffect(() => {
    void ensure(config.i18nNamespaces);
  }, [config.i18nNamespaces, ensure, locale]);

  const loadPermissions = useCallback(async () => {
    if (providedPermissions) return;
    setPermissionsLoading(true);
    setPermissionsError(undefined);
    try {
      setLoadedPermissions(await get<RuntimePermissions>(
        `${config.baseUrl}/workbench-permissions`,
      ));
    } catch (error) {
      setPermissionsError(resolveApiErrorMessage(
        error,
        t('ai.mcp.center.error.permissions', 'MCP 中心权限加载失败'),
      ));
    } finally {
      setPermissionsLoading(false);
    }
  }, [config.baseUrl, providedPermissions, t]);

  useEffect(() => {
    void loadPermissions();
  }, [loadPermissions]);

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
        <div style={{height: '100%', overflow: 'auto'}}>
          <RuntimePools
            poolsUrl={config.poolsUrl}
            secretsUrl={config.secretsUrl}
            serversUrl={config.baseUrl}
            canManage={Boolean(permissions?.managePools)}
          />
        </div>
      ),
    } : null,
    canViewWorkloads ? {
      key: 'workloads',
      label: t('ai.runtime.tab.workloads', 'Workloads'),
      children: (
        <div style={{height: '100%', overflow: 'auto'}}>
          <RuntimeWorkloads
            workloadsUrl={config.workloadsUrl}
            secretsUrl={config.secretsUrl}
            serversUrl={config.baseUrl}
            canManage={Boolean(permissions?.manageWorkloads)}
          />
        </div>
      ),
    } : null,
    permissions?.viewNodes ? {
      key: 'nodes',
      label: t('ai.runtime.tab.nodes', 'Nodes（平台）'),
      children: (
        <div style={{height: '100%', overflow: 'auto'}}>
          <RuntimeNodes nodesUrl={config.nodesUrl}/>
        </div>
      ),
    } : null,
    canViewSecrets ? {
      key: 'secrets',
      label: t('ai.runtime.tab.secrets', 'Secrets'),
      children: (
        <div style={{height: '100%', overflow: 'auto'}}>
          <RuntimeSecrets
            secretsUrl={config.secretsUrl}
            canManage={Boolean(permissions?.manageSecrets)}
          />
        </div>
      ),
    } : null,
  ].filter((item): item is NonNullable<typeof item> => item !== null), [
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
          <Button size="small" onClick={() => void loadPermissions()}>
            {t('ai.mcp.action.retry', '重试')}
          </Button>
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

export default Runtime;
