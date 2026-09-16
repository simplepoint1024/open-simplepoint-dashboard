import { defineMockModule, defineResource } from '../../../runtime';
import handlers from './handlers';

const contract = defineResource({
  id: 'auditing.redis.entries',
  scope: 'auditing',
  backendModule: 'simplepoint-plugin-auditing-redis-implementation',
  backendController: 'RedisMonitoringController',
  contextPath: '/auditing',
  paths: ['/redis/entries'],
  entity: 'RedisEntry',
});

export default defineMockModule(contract, handlers);
