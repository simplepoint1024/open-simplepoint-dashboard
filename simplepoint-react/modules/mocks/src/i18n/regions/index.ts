import { defineMockModule, defineResource } from '../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'i18n.regions',
  scope: 'common',
  backendModule: 'simplepoint-plugin-i18n-rest',
  backendController: 'I18nRegionsController',
  contextPath: '/common',
  paths: ['/i18n/regions'],
  entity: 'Region',
  i18nNamespaces: ['regions'],
});

export default defineMockModule(contract, handlers);
