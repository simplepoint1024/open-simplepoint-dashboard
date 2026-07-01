import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'rbac.core.permissions',
  scope: 'common',
  backendModule: 'simplepoint-plugin-rbac-core-rest',
  backendController: 'PermissionsController',
  contextPath: '/common',
  paths: ['/permissions'],
  entity: 'Permissions',
  i18nNamespaces: ['permissions'],
});

export default defineMockModule(contract, handlers);
