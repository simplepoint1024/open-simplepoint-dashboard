import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'auditing.logging.permission-change-logs',
  scope: 'auditing',
  backendModule: 'simplepoint-plugin-auditing-logging-rest',
  backendController: 'PermissionChangeLogController',
  contextPath: '/auditing',
  paths: ['/logging/permission-change-logs'],
  entity: 'PermissionChangeLog',
});

export default defineMockModule(contract, handlers);
