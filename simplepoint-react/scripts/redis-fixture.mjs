import {createRedisSnapshotFixture, redisSchema} from '../modules/mocks/src/auditing/redis/entries/queryFixture.ts';

export const redisRoute = {id: 'redis-query', code: 'auditing.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/redis-query', component: 'auditing/monitoring/Redis', label: 'Redis 查询测试', scopeTypes: ['PLATFORM']};

export function createRedisFixture() {
  const rows = Array.from({length: 520}, (_, index) => ({key: `Item${String(index).padStart(3, '0')}`,
    type: 'STRING', size: index, persistent: true, ttlSeconds: null, editable: true, observedAt: '2026-09-19T00:00:00Z'}));
  const snapshots = createRedisSnapshotFixture(() => rows);
  const requests = [], details = [];
  let failQuery = false, failDetail = false, overrideDetail;
  return {rows, requests, details, snapshots,
    failQuery() {failQuery = true;}, failDetail() {failDetail = true;},
    detail(value) {overrideDetail = value;},
    async handle(req, url, respond, expectedFailure) {
      const base = '/auditing/redis/entries';
      if (!url.pathname.startsWith(base)) return false;
      if (req.method !== 'GET') throw Error('Redis query smoke must not mutate real or fixture keys');
      if (url.pathname === `${base}/query/schema`) return respond(redisSchema), true;
      if (url.pathname === `${base}/query`) {
        requests.push(new URLSearchParams(url.searchParams));
        if (failQuery) {failQuery = false; expectedFailure(400); return respond({code: 'INVALID_QUERY', message: '测试条件失效'}, 400), true;}
        return respond(snapshots.query(url.searchParams)), true;
      }
      if (url.pathname === `${base}/detail`) {
        const key = url.searchParams.get('key');
        details.push(key);
        if (failDetail) {failDetail = false; expectedFailure(404); return respond({message: '键已过期（测试）'}, 404), true;}
        return respond({...rows.find(row => row.key === key), value: 'full value', valueEncoding: 'UTF-8', ...overrideDetail}), true;
      }
      throw Error(`Redis page used an obsolete/unknown endpoint: ${url.pathname}`);
    },
  };
}
