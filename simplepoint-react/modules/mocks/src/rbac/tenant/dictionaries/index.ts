import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'rbac.tenant.dictionaries',
  scope: 'common',
  backendModule: 'simplepoint-plugin-rbac-tenant-rest',
  backendController: 'DictionaryController, DictionaryItemController',
  contextPath: '/common',
  paths: ['/dictionaries', '/dictionary-items', '/platform/dictionaries', '/platform/dictionary-items'],
  entity: 'Dictionary',
  i18nNamespaces: ['dictionaries'],
});

export default defineMockModule(contract, handlers);
