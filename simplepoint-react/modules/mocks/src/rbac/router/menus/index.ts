import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'rbac.router.menus',
  scope: 'common',
  backendModule: 'simplepoint-plugin-rbac-router-rest',
  backendController: 'MenusController',
  contextPath: '/common',
  paths: ['/menus'],
  entity: 'Menu',
  i18nNamespaces: ['menus', 'permissions'],
});

export default defineMockModule(contract, handlers);
