import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'rbac.tenant.applications',
  scope: 'common',
  backendModule: 'simplepoint-plugin-rbac-tenant-rest',
  backendController: 'ApplicationController',
  contextPath: '/common',
  paths: ['/applications', '/platform/applications'],
  entity: 'Application',
  i18nNamespaces: ['applications'],
});

export default defineMockModule(contract, handlers);
