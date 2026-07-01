import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'rbac.core.data-scopes',
  scope: 'common',
  backendModule: 'simplepoint-plugin-rbac-core-rest',
  backendController: 'DataScopeController',
  contextPath: '/common',
  paths: ['/data-scopes'],
  entity: 'DataScope',
  i18nNamespaces: ['data-scopes'],
});

export default defineMockModule(contract, handlers);
