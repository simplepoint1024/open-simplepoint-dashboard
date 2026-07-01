import { defineMockModule, defineResource } from '../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'i18n.messages',
  scope: 'common',
  backendModule: 'simplepoint-plugin-i18n-rest',
  backendController: 'I18nMessagesController',
  contextPath: '/common',
  paths: ['/i18n/messages'],
  entity: 'Message',
  i18nNamespaces: ['messages'],
});

export default defineMockModule(contract, handlers);
