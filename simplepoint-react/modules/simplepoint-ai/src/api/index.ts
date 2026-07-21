import {contextPath} from '@/services';

export default {
  'platform.ai-workspace': {
    baseUrl: `${contextPath}/platform/ai`,
    i18nNamespaces: ['ai-workspace'],
    name: 'ai-workspace',
  },
  'platform.ai-providers': {
    baseUrl: `${contextPath}/platform/ai/providers`,
    i18nNamespaces: ['ai-model-providers'],
    name: 'ai-providers',
  },
  'platform.ai-models': {
    baseUrl: `${contextPath}/platform/ai/models`,
    i18nNamespaces: ['ai-model-providers'],
    name: 'ai-models',
  },
  'platform.ai-inference': {
    baseUrl: `${contextPath}/platform/ai/inference`,
    modelsUrl: `${contextPath}/platform/ai/models/available`,
    i18nNamespaces: ['ai-runtime'],
    name: 'ai-inference',
  },
  'platform.ai-invocations': {
    baseUrl: `${contextPath}/platform/ai/invocations`,
    i18nNamespaces: ['ai-runtime'],
    name: 'ai-invocations',
  },
  'platform.ai-knowledge-bases': {
    baseUrl: `${contextPath}/platform/ai/knowledge-bases`,
    i18nNamespaces: ['ai-knowledge-bases'],
    name: 'ai-knowledge-bases',
  },
  'tenant.ai-providers': {
    baseUrl: `${contextPath}/tenant/ai/providers`,
    i18nNamespaces: ['ai-model-providers'],
    name: 'tenant-ai-providers',
  },
  'tenant.ai-models': {
    baseUrl: `${contextPath}/tenant/ai/models`,
    i18nNamespaces: ['ai-model-providers'],
    name: 'tenant-ai-models',
  },
  'tenant.ai-inference': {
    baseUrl: `${contextPath}/tenant/ai/inference`,
    modelsUrl: `${contextPath}/tenant/ai/models/available`,
    i18nNamespaces: ['ai-runtime'],
    name: 'tenant-ai-inference',
  },
  'tenant.ai-invocations': {
    baseUrl: `${contextPath}/tenant/ai/invocations`,
    i18nNamespaces: ['ai-runtime'],
    name: 'tenant-ai-invocations',
  },
  'tenant.ai-knowledge-bases': {
    baseUrl: `${contextPath}/tenant/ai/knowledge-bases`,
    i18nNamespaces: ['ai-knowledge-bases'],
    name: 'tenant-ai-knowledge-bases',
  },
};
