import {createRedisSnapshotFixture, redisSchema} from '../modules/mocks/src/auditing/redis/entries/queryFixture.ts';

export const redisRoute = {id: 'redis-query', code: 'auditing.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/redis-query', component: 'auditing/monitoring/Redis', label: 'Redis 查询测试', scopeTypes: ['PLATFORM']};

export function createRedisFixture() {
  const rows = Array.from({length: 520}, (_, index) => ({key: `Item${String(index).padStart(3, '0')}`,
    type: 'STRING', size: index, persistent: true, ttlSeconds: null, editable: true, observedAt: '2026-09-19T00:00:00Z'}));
  const snapshots = createRedisSnapshotFixture(() => rows);
  const requests = [], details = [];
  const values = new Map(rows.map(row=>[row.key, 'full value'])), writes = [];
  let failQuery = false, failDetail = false, overrideDetail, mutations = false, failedWrite = false, readonly = false;
  return {rows, requests, details, snapshots, writes, values,
    enableWrites() {
      mutations = true;
      for (const key of ['a', 'b', ' a,b ']) {
        rows.unshift({key, type: 'STRING', size: 10, persistent: true, ttlSeconds: null, editable: true, observedAt: '2026-09-19T00:00:00Z'});
        values.set(key, 'full value');
      }
    },
    failWrite() {failedWrite = true;}, readonly(value) {readonly = value;},
    failQuery() {failQuery = true;}, failDetail() {failDetail = true;},
    detail(value) {overrideDetail = value;},
    async handle(req, url, respond, expectedFailure) {
      const base = '/auditing/redis/entries';
      if (!url.pathname.startsWith(base)) return false;
      if (req.method !== 'GET') {
        if (!mutations || readonly || url.pathname !== base) throw Error('Redis query/readonly smoke must not mutate keys');
        let raw = ''; for await (const chunk of req) raw += chunk;
        const data = raw ? JSON.parse(raw) : undefined, keys = url.searchParams.getAll('keys');
        writes.push({method: req.method, data, keys}); await new Promise(resolve=>setTimeout(resolve, 300));
        if (failedWrite) {failedWrite = false; expectedFailure(503); return respond({message: 'Redis 保存失败（测试）'}, 503), true;}
        if (req.method === 'DELETE') {
          if (keys.length !== 1) throw Error('Deletion must preserve a single exact key parameter');
          values.delete(keys[0]); const index = rows.findIndex(row=>row.key===keys[0]); if (index >= 0) rows.splice(index, 1);
        } else {
          if (typeof data.key !== 'string' || !data.key || typeof data.value !== 'string' || typeof data.persistent !== 'boolean'
            || !data.persistent && (!Number.isSafeInteger(data.ttlSeconds) || data.ttlSeconds < 1)
            || data.persistent && data.ttlSeconds !== null) throw Error('Redis must submit actual text and valid TTL semantics');
          values.set(data.key, data.value);
          let row = rows.find(row=>row.key===data.key);
          if (!row) {row = {key: data.key, type: 'STRING', editable: true, observedAt: '2026-09-19T00:00:00Z'}; rows.unshift(row);}
          Object.assign(row, {persistent: data.persistent, ttlSeconds: data.ttlSeconds, size: Buffer.byteLength(data.value)});
        }
        snapshots.invalidate(); return respond(null), true;
      }
      if (url.pathname === `${base}/query/schema`) {
        const schema = structuredClone(redisSchema); if (readonly) schema.buttons = schema.buttons.filter(button=>button.key==='view');
        return respond(schema), true;
      }
      if (url.pathname === `${base}/query`) {
        requests.push(new URLSearchParams(url.searchParams));
        if (failQuery) {failQuery = false; expectedFailure(400); return respond({code: 'INVALID_QUERY', message: '测试条件失效'}, 400), true;}
        return respond(snapshots.query(url.searchParams)), true;
      }
      if (url.pathname === `${base}/detail`) {
        const key = url.searchParams.get('key');
        details.push(key);
        if (failDetail) {failDetail = false; expectedFailure(404); return respond({message: '键已过期（测试）'}, 404), true;}
        return respond({...rows.find(row => row.key === key), value: values.get(key), valueEncoding: 'UTF-8', ...overrideDetail}), true;
      }
      throw Error(`Redis page used an obsolete/unknown endpoint: ${url.pathname}`);
    },
  };
}
