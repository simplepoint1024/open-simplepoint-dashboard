import {contextPath} from '@/services';

export default {
  'ai-workbench.workspace': {
    i18nNamespaces: ['ai-workspace'],
    name: 'ai-workbench-workspace',
  },
  'ai-workbench.providers': {
    baseUrl: `${contextPath}/providers`,
    i18nNamespaces: ['ai-providers'],
    name: 'ai-workbench-providers',
  },
  'ai-workbench.models': {
    baseUrl: `${contextPath}/models`,
    debugUrl: (modelDefinitionId: string) =>
      `${contextPath}/models/${modelDefinitionId}/debug/stream`,
    i18nNamespaces: ['ai-models', 'ai-model-debug'],
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
    i18nNamespaces: ['ai-knowledge-bases'],
    name: 'ai-workbench-knowledge-bases',
  },
  'ai-workbench.mcp-gateway': {
    baseUrl: `${contextPath}/mcp/gateway`,
    statusUrl: `${contextPath}/mcp/gateway/status`,
    i18nNamespaces: ['ai-mcp'],
    name: 'ai-workbench-mcp-gateway',
  },
  'ai-workbench.mcp-servers': {
    baseUrl: `${contextPath}/mcp/servers`,
    poolsUrl: `${contextPath}/runtime/pools`,
    workloadsUrl: `${contextPath}/runtime/workloads`,
    secretsUrl: `${contextPath}/runtime/secrets`,
    i18nNamespaces: ['ai-mcp', 'ai-runtime'],
    name: 'ai-workbench-mcp-servers',
  },
  'ai-workbench.mcp-publications': {
    baseUrl: `${contextPath}/mcp/publications`,
    serversUrl: `${contextPath}/mcp/servers`,
    i18nNamespaces: ['ai-mcp'],
    name: 'ai-workbench-mcp-publications',
  },
  'ai-workbench.tools': {
    serversUrl: `${contextPath}/mcp/servers`,
    i18nNamespaces: ['ai-mcp'],
    name: 'ai-workbench-tools',
  },
  'ai-workbench.skills': {
    baseUrl: `${contextPath}/skills`,
    i18nNamespaces: ['ai-skills'],
    name: 'ai-workbench-skills',
  },
  'ai-workbench.agents': {
    baseUrl: `${contextPath}/agents`,
    modelsUrl: `${contextPath}/models`,
    skillsUrl: `${contextPath}/skills`,
    i18nNamespaces: ['ai-agents'],
    name: 'ai-workbench-agents',
  },
  'ai-workbench.runtime': {
    nodesUrl: `${contextPath}/runtime/nodes`,
    poolsUrl: `${contextPath}/runtime/pools`,
    workloadsUrl: `${contextPath}/runtime/workloads`,
    secretsUrl: `${contextPath}/runtime/secrets`,
    serversUrl: `${contextPath}/mcp/servers`,
    i18nNamespaces: ['ai-runtime'],
    name: 'ai-workbench-runtime',
  },
};
