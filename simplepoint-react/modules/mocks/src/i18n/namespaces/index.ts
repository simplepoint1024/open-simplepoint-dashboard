import { defineMockModule, defineResource } from '../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'i18n.namespaces',
  scope: 'common',
  backendModule: 'simplepoint-plugin-i18n-rest',
  backendController: 'I18nNamespaceController',
  contextPath: '/common',
  paths: ['/i18n/namespaces'],
  entity: 'Namespace',
  i18nNamespaces: ['namespaces'],
});

export default defineMockModule(contract, handlers);
