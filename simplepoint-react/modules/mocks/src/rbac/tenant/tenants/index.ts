import { defineMockModule, defineResource } from '../../../runtime';
import contextHandlers from './context-handlers';
import handlers from './handlers';

const contract = defineResource({
  id: 'rbac.tenant.tenants',
  scope: 'common',
  backendModule: 'simplepoint-plugin-rbac-tenant-rest',
  backendController: 'TenantController',
  contextPath: '/common',
  paths: ['/tenants', '/platform/tenants'],
  entity: 'Tenant',
  i18nNamespaces: ['tenants'],
});

export default defineMockModule(contract, [...handlers, ...contextHandlers]);
