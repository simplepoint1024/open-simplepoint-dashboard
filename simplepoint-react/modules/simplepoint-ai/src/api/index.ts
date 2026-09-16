import {contextPath} from '@/services';

export default {
  'ai-workbench.providers': {
    baseUrl: `${contextPath}/providers`,
    i18nNamespaces: ['ai-common', 'ai-providers', 'ai-models'],
    name: 'ai-workbench-providers',
  },
  'ai-workbench.models': {
    baseUrl: `${contextPath}/models`,
    debugUrl: (modelDefinitionId: string) =>
      `${contextPath}/models/${modelDefinitionId}/debug/stream`,
    i18nNamespaces: ['ai-common', 'ai-models', 'ai-model-debug'],
    name: 'ai-workbench-models',
  },
  'ai-workbench.api-keys': {
    baseUrl: `${contextPath}/api-keys`,
    i18nNamespaces: ['ai-api-keys'],
    name: 'ai-workbench-api-keys',
  },
  'ai-workbench.billing': {
    baseUrl: `${contextPath}/billing`,
    summaryUrl: `${contextPath}/billing/summary`,
    invocationsUrl: `${contextPath}/billing/invocations`,
    i18nNamespaces: ['ai-billing', 'ai-invocations'],
    name: 'ai-workbench-billing',
  },
  'ai-workbench.knowledge-bases': {
    baseUrl: `${contextPath}/knowledge-bases`,
    i18nNamespaces: ['ai-common', 'ai-knowledge-bases'],
    name: 'ai-workbench-knowledge-bases',
  },
  'ai-workbench.catalog': {
    baseUrl: `${contextPath}/catalog`,
    syncUrl: `${contextPath}/catalog/sync`,
    syncStatusUrl: `${contextPath}/catalog/sync-status`,
    i18nNamespaces: ['ai-catalog'],
    name: 'ai-workbench-catalog',
  },
  'ai-workbench.mcp-servers': {
    baseUrl: `${contextPath}/mcp/servers`,
    gatewayStatusUrl: `${contextPath}/mcp/gateway/status`,
    publicationsUrl: `${contextPath}/mcp/publications`,
    nodesUrl: `${contextPath}/runtime/nodes`,
    poolsUrl: `${contextPath}/runtime/pools`,
    workloadsUrl: `${contextPath}/runtime/workloads`,
    secretsUrl: `${contextPath}/runtime/secrets`,
    i18nNamespaces: ['ai-common', 'ai-mcp', 'ai-runtime'],
    name: 'ai-workbench-mcp-servers',
  },
  'ai-workbench.skills': {
    baseUrl: `${contextPath}/skills`,
    registryUrl: `${contextPath}/skills/managed-registry`,
    i18nNamespaces: ['ai-common', 'ai-skills'],
    name: 'ai-workbench-skills',
  },
  'ai-workbench.agents': {
    baseUrl: `${contextPath}/agents`,
    modelsUrl: `${contextPath}/models`,
    skillsUrl: `${contextPath}/skills`,
    i18nNamespaces: ['ai-common', 'ai-agents', 'ai-models'],
    name: 'ai-workbench-agents',
  },
  'ai-workbench.workflows': {
    baseUrl: `${contextPath}/workflows`,
    agentsUrl: `${contextPath}/agents`,
    skillsUrl: `${contextPath}/skills`,
    i18nNamespaces: ['ai-common', 'ai-workflows'],
    name: 'ai-workbench-workflows',
  },
};
