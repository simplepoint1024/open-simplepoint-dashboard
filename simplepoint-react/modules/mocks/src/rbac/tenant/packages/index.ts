import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'rbac.tenant.packages',
  scope: 'common',
  backendModule: 'simplepoint-plugin-rbac-tenant-rest',
  backendController: 'PackageController',
  contextPath: '/common',
  paths: ['/packages', '/platform/packages'],
  entity: 'Package',
  i18nNamespaces: ['packages'],
});

export default defineMockModule(contract, handlers);
