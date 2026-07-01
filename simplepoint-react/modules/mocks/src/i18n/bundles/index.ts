import { defineMockModule, defineResource } from '../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'i18n.bundles',
  scope: 'common',
  backendModule: 'simplepoint-plugin-i18n-rest',
  backendController: 'I18nLanguagesController, I18nMessagesController',
  contextPath: '/common',
  paths: ['/i18n/languages/mapping', '/i18n/messages/mapping'],
  entity: 'MessageBundle',
  i18nNamespaces: ['common', 'menu'],
});

export default defineMockModule(contract, handlers);
