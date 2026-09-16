import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'auditing.logging.login-logs',
  scope: 'auditing',
  backendModule: 'simplepoint-plugin-auditing-logging-implementation',
  backendController: 'LoginLogController',
  contextPath: '/auditing',
  paths: ['/logging/login-logs'],
  entity: 'LoginLog',
});

export default defineMockModule(contract, handlers);
