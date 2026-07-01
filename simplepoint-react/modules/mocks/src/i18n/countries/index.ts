import { defineMockModule, defineResource } from '../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'i18n.countries',
  scope: 'common',
  backendModule: 'simplepoint-plugin-i18n-rest',
  backendController: 'I18nCountriesController',
  contextPath: '/common',
  paths: ['/i18n/countries'],
  entity: 'Countries',
  i18nNamespaces: ['countries'],
});

export default defineMockModule(contract, handlers);
