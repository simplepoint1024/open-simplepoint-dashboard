import { defineMockModule, defineResource } from '../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'storage.object-storage',
  scope: 'storage',
  backendModule: 'simplepoint-plugin-storage',
  backendController: 'ObjectStorageController',
  contextPath: '/common',
  paths: ['/platform/object-storage'],
  entity: 'ObjectStorageObject',
  i18nNamespaces: ['storage'],
});

export default defineMockModule(contract, handlers);
