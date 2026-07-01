import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'rbac.core.roles',
  scope: 'common',
  backendModule: 'simplepoint-plugin-rbac-core-rest',
  backendController: 'RoleController',
  contextPath: '/common',
  paths: ['/roles'],
  entity: 'Role',
  i18nNamespaces: ['roles', 'permissions'],
});

export default defineMockModule(contract, handlers);
