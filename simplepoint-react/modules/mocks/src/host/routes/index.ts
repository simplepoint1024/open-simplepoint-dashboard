import { defineMockModule, defineResource } from '../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'host.routes',
  scope: 'host',
  backendModule: 'simplepoint-host',
  backendController: 'MenusController',
  contextPath: '/common',
  paths: ['/menus/service-routes'],
  entity: 'ServiceMenuResult',
  description: 'Host bootstrap routes and micro-frontend service entries.',
});

export default defineMockModule(contract, handlers);
