import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'rbac.core.field-scopes',
  scope: 'common',
  backendModule: 'simplepoint-service-common',
  backendController: 'FieldScopeController',
  contextPath: '/common',
  paths: ['/field-scopes'],
  entity: 'FieldScope',
  i18nNamespaces: ['field-scopes'],
});

export default defineMockModule(contract, handlers);
