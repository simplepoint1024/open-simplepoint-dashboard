import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'auditing.logging.error-logs',
  scope: 'auditing',
  backendModule: 'simplepoint-plugin-auditing-logging-rest',
  backendController: 'ErrorLogController',
  contextPath: '/auditing',
  paths: ['/logging/error-logs'],
  entity: 'ErrorLog',
});

export default defineMockModule(contract, handlers);
