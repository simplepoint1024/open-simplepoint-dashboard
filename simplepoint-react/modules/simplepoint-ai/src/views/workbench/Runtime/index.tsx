import api from '@/api';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, Tabs} from 'antd';
import {useEffect} from 'react';
import RuntimeNodes from './RuntimeNodes';
import RuntimePools from './RuntimePools';
import RuntimeSecrets from './RuntimeSecrets';
import RuntimeWorkloads from './RuntimeWorkloads';

const Runtime = () => {
  const config = api['ai-workbench.runtime'];
  const {t, ensure, locale} = useI18n();

  useEffect(() => {
    void ensure(config.i18nNamespaces);
  }, [config.i18nNamespaces, ensure, locale]);

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
        style={{marginBottom: 16, flexShrink: 0}}
        message={t('ai.runtime.title', 'OCI MCP 运行时管理')}
        description={t(
          'ai.runtime.description',
          '管理托管 MCP Server 的声明式副本、实际 Workload、执行节点和加密 Secret；作用域由当前平台或租户上下文自动确定。',
        )}
      />
      <Tabs
        defaultActiveKey="pools"
        style={{flex: 1, minHeight: 0}}
        styles={{
          body: {minHeight: 0, overflow: 'hidden'},
          content: {height: '100%'},
        }}
        items={[
          {
            key: 'pools',
            label: t('ai.runtime.tab.pools', 'Runtime Pools'),
            children: (
              <div style={{height: '100%', overflow: 'auto'}}>
                <RuntimePools
                  poolsUrl={config.poolsUrl}
                  secretsUrl={config.secretsUrl}
                  serversUrl={config.serversUrl}
                />
              </div>
            ),
          },
          {
            key: 'workloads',
            label: t('ai.runtime.tab.workloads', 'Workloads'),
            children: (
              <div style={{height: '100%', overflow: 'auto'}}>
                <RuntimeWorkloads
                  workloadsUrl={config.workloadsUrl}
                  secretsUrl={config.secretsUrl}
                  serversUrl={config.serversUrl}
                />
              </div>
            ),
          },
          {
            key: 'nodes',
            label: t('ai.runtime.tab.nodes', 'Nodes（平台）'),
            children: (
              <div style={{height: '100%', overflow: 'auto'}}>
                <RuntimeNodes nodesUrl={config.nodesUrl}/>
              </div>
            ),
          },
          {
            key: 'secrets',
            label: t('ai.runtime.tab.secrets', 'Secrets'),
            children: (
              <div style={{height: '100%', overflow: 'auto'}}>
                <RuntimeSecrets secretsUrl={config.secretsUrl}/>
              </div>
            ),
          },
        ]}
      />
    </div>
  );
};

export default Runtime;
