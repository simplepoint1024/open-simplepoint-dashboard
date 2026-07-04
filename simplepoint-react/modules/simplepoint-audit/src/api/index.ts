import {contextPath} from "@/services";

export default {
  'monitoring-login-logs': {
    baseUrl: `${contextPath}/logging/login-logs`,
    i18nNamespaces: ['monitoring-login-logs'],
    name: 'login-logs'
  },
  'monitoring-error-logs': {
    baseUrl: `${contextPath}/logging/error-logs`,
    i18nNamespaces: ['monitoring-error-logs'],
    name: 'error-logs'
  },
  'monitoring-service-rate-limit-rules': {
    baseUrl: `${contextPath}/rate-limit/service-rules`,
    i18nNamespaces: ['monitoring-service-rate-limit-rules'],
    name: 'service-rate-limit-rules'
  },
  'monitoring-endpoint-rate-limit-rules': {
    baseUrl: `${contextPath}/rate-limit/endpoint-rules`,
    i18nNamespaces: ['monitoring-endpoint-rate-limit-rules'],
    name: 'endpoint-rate-limit-rules'
  },
  'monitoring-redis': {
    baseUrl: `${contextPath}/redis/entries`,
    i18nNamespaces: ['monitoring-redis'],
    name: 'redis'
  },
  'monitoring-resource-grant-logs': {
    baseUrl: `${contextPath}/logging/resource-grant-logs`,
    i18nNamespaces: ['monitoring-resource-grant-logs'],
    name: 'resource-grant-logs'
  }
}
