import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'rbac.router.microapps',
  scope: 'common',
  backendModule: 'simplepoint-service-common',
  backendController: 'MicroAppController',
  contextPath: '/common',
  paths: ['/ops/microapps'],
  entity: 'MicroApp',
  i18nNamespaces: ['microapps'],
});

export default defineMockModule(contract, handlers);
