import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'rbac.router.resources',
  scope: 'common',
  backendModule: 'simplepoint-plugin-rbac-router-rest',
  backendController: 'ResourcesController',
  contextPath: '/common',
  paths: ['/resources'],
  entity: 'Resource',
  i18nNamespaces: ['resources'],
});

export default defineMockModule(contract, handlers);
