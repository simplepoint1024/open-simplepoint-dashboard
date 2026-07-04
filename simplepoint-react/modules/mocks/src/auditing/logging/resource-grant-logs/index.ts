import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'auditing.logging.resource-grant-logs',
  scope: 'auditing',
  backendModule: 'simplepoint-plugin-auditing-logging-rest',
  backendController: 'ResourceGrantLogController',
  contextPath: '/auditing',
  paths: ['/logging/resource-grant-logs'],
  entity: 'ResourceGrantLog',
});

export default defineMockModule(contract, handlers);
