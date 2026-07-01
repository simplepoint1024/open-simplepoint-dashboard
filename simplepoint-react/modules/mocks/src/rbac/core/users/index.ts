import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'rbac.core.users',
  scope: 'common',
  backendModule: 'simplepoint-plugin-rbac-core-rest',
  backendController: 'UsersController',
  contextPath: '/common',
  paths: ['/users'],
  entity: 'User',
  i18nNamespaces: ['users', 'roles'],
  description: 'RBAC user management and role assignment endpoints.',
});

export default defineMockModule(contract, handlers);
