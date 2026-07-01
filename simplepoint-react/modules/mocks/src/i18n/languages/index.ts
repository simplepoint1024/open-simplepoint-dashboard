import { defineMockModule, defineResource } from '../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'i18n.languages',
  scope: 'common',
  backendModule: 'simplepoint-plugin-i18n-rest',
  backendController: 'I18nLanguagesController',
  contextPath: '/common',
  paths: ['/i18n/languages'],
  entity: 'Language',
  i18nNamespaces: ['languages'],
});

export default defineMockModule(contract, handlers);
