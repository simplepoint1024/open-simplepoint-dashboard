import {contextPath} from "@/services";

export default {
  'monitoring-login-logs': {
    baseUrl: `${contextPath}/logging/login-logs`,
    i18nNamespaces: [],
    name: 'login-logs'
  },
  'monitoring-error-logs': {
    baseUrl: `${contextPath}/logging/error-logs`,
    i18nNamespaces: [],
    name: 'error-logs'
  },
  'monitoring-service-rate-limit-rules': {
    baseUrl: `${contextPath}/rate-limit/service-rules`,
    i18nNamespaces: [],
    name: 'service-rate-limit-rules'
  },
  'monitoring-endpoint-rate-limit-rules': {
    baseUrl: `${contextPath}/rate-limit/endpoint-rules`,
    i18nNamespaces: [],
    name: 'endpoint-rate-limit-rules'
  },
  'monitoring-redis': {
    baseUrl: `${contextPath}/redis/entries`,
    i18nNamespaces: [],
    name: 'redis'
  },
  'monitoring-permission-change-logs': {
    baseUrl: `${contextPath}/logging/permission-change-logs`,
    i18nNamespaces: [],
    name: 'permission-change-logs'
  }
}
