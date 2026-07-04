import {contextPath} from "@/services";

export default {
  'oidc-clients': {
    baseUrl: `${contextPath}/oidc/clients`,
    i18nNamespaces: ['clients'],
    name: 'oidc-clients',
  },
  'rbac-resources':{
    baseUrl: `${contextPath}/resources`,
    i18nNamespaces: ['resources'],
    name: 'resources',
  },
  'rbac-roles':{
    baseUrl: `${contextPath}/roles`,
    i18nNamespaces: ['roles','resources'],
    name: 'roles'
  },
  'rbac-users':{
    baseUrl: `${contextPath}/users`,
    i18nNamespaces: ['users','roles'],
    name: 'users'
  },
  'rbac-access-center': {
    baseUrl: `${contextPath}/access-center`,
    i18nNamespaces: ['access-center', 'roles', 'resources', 'data-scopes', 'field-scopes', 'table', 'common'],
    name: 'access-center'
  },
  'i18n-countries': {
    baseUrl: `${contextPath}/i18n/countries`,
    i18nNamespaces: ['countries'],
    name: 'countries'
  },
  'i18n-languages': {
    baseUrl: `${contextPath}/i18n/languages`,
    i18nNamespaces: ['languages'],
    name: 'languages'
  },
  'i18n-namespaces': {
    baseUrl: `${contextPath}/i18n/namespaces`,
    i18nNamespaces: ['namespaces'],
    name: 'namespaces'
  },
  'i18n-regions': {
    baseUrl: `${contextPath}/i18n/regions`,
    i18nNamespaces: ['regions'],
    name: 'regions'
  },
  'i18n-messages': {
    baseUrl: `${contextPath}/i18n/messages`,
    i18nNamespaces: ['messages'],
    name: 'messages'
  },
  'i18n-timezones': {
    baseUrl: `${contextPath}/i18n/timezones`,
    i18nNamespaces: ['timezones'],
    name: 'timezones'
  },
  'ops-microapps': {
    baseUrl: `${contextPath}/ops/microapps`,
    i18nNamespaces: ['microapps'],
    name: 'microapps'
  },
  'platform.packages': {
    baseUrl: `${contextPath}/platform/packages`,
    i18nNamespaces: ['packages', 'applications'],
    name: 'packages'
  },
  'platform.tenants': {
    baseUrl: `${contextPath}/platform/tenants`,
    i18nNamespaces: ['tenants', 'packages', 'users'],
    name: 'tenants'
  },
  'platform.dictionaries': {
    baseUrl: `${contextPath}/platform/dictionaries`,
    i18nNamespaces: ['dictionaries'],
    name: 'dictionaries'
  },
  'platform.organizations': {
    baseUrl: `${contextPath}/platform/organizations`,
    i18nNamespaces: ['organizations'],
    name: 'organizations'
  },
  'platform.object-storage': {
    baseUrl: `${contextPath}/platform/object-storage`,
    i18nNamespaces: ['storage'],
    name: 'object-storage'
  },
  'platform.dictionary-items': {
    baseUrl: `${contextPath}/platform/dictionary-items`,
    i18nNamespaces: ['dictionaries'],
    name: 'dictionary-items'
  },
  'platform.applications': {
    baseUrl: `${contextPath}/platform/applications`,
    i18nNamespaces: ['applications', 'resources'],
    name: 'applications'
  },
  'rbac-data-scopes': {
    baseUrl: `${contextPath}/data-scopes`,
    i18nNamespaces: ['data-scopes'],
    name: 'data-scopes',
  },
  'rbac-field-scopes': {
    baseUrl: `${contextPath}/field-scopes`,
    i18nNamespaces: ['field-scopes'],
    name: 'field-scopes',
  },
}
