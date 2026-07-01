import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'rbac.tenant.features',
  scope: 'common',
  backendModule: 'simplepoint-plugin-rbac-tenant-rest',
  backendController: 'FeatureController',
  contextPath: '/common',
  paths: ['/features', '/platform/features'],
  entity: 'Feature',
  i18nNamespaces: ['features'],
});

export default defineMockModule(contract, handlers);
