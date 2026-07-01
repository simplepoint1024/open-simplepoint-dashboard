import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'rbac.tenant.organizations',
  scope: 'common',
  backendModule: 'simplepoint-plugin-rbac-tenant-rest',
  backendController: 'OrganizationController',
  contextPath: '/common',
  paths: ['/organizations', '/platform/organizations'],
  entity: 'Organization',
  i18nNamespaces: ['organizations'],
});

export default defineMockModule(contract, handlers);
