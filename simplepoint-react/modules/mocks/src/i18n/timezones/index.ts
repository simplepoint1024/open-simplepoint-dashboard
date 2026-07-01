import { defineMockModule, defineResource } from '../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'i18n.timezones',
  scope: 'common',
  backendModule: 'simplepoint-plugin-i18n-rest',
  backendController: 'I18nTimeZonesController',
  contextPath: '/common',
  paths: ['/i18n/timezones'],
  entity: 'TimeZone',
  i18nNamespaces: ['timezones'],
});

export default defineMockModule(contract, handlers);
