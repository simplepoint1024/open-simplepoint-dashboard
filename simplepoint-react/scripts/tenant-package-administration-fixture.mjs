import {readFileSync} from 'node:fs';
import {fixturePage} from './query-fixture.mjs';
const schema = JSON.parse(readFileSync(new URL('./tenant-package-administration-contracts/schema.json', import.meta.url), 'utf8'));
const fields = Object.fromEntries(Object.entries(schema.schema['x-query'].fields).map(([name, field]) => [name,
  {type: ['Integer', 'Long', 'int', 'long'].includes(field.javaType) ? 'integer' : 'string'}]));
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

/** Exact allocation retry after a committed write whose HTTP response was lost. */
export function createPackageAdministrationFixture(tenants, packages, packageSchema, page) {
  const instances = new Map(tenants.map(tenant => [tenant.id, []]));
  const receipts = new Map(), writes = [], requests = [];
  let fail = false;
  return {instances, writes, requests, failAfterCommit() {fail = true;},
    async handle(req, url, respond, expectedFailure, mode) {
      const match = /^\/common\/platform\/tenants\/([^/]+)\/package-instances(?:\/(.*))?$/.exec(url.pathname);
      if (url.pathname === '/common/platform/packages/items' && !url.searchParams.has('tenantId')) {
        if (req.method !== 'GET') throw Error('Package picker is read-only');
        respond(page(packages, packageSchema, url)); return true;
      }
      if (!match) return false;
      const tenant = decodeURIComponent(match[1]), suffix = match[2] ?? '';
      if (!instances.has(tenant)) throw Error('Package administration lost its tenant');
      requests.push({path: url.pathname, method: req.method, tenant, query: new URLSearchParams(url.search)});
      if (mode === 'viewer') {expectedFailure(403); respond({messageKey: 'tenant.packages.error.PLATFORM_ADMIN_REQUIRED'}, 403); return true;}
      const rows = instances.get(tenant);
      if (req.method === 'GET') {
        if (suffix === 'schema') {respond(schema); return true;}
        if (!suffix) {respond(fixturePage(rows, fields, url)); return true;}
        const row = rows.find(row => suffix === row.id || suffix.startsWith(row.id + '/'));
        if (!row) throw Error('Foreign package instance');
        if (suffix.endsWith('/resources')) {
          const number = Number(url.searchParams.get('page')), size = Number(url.searchParams.get('size'));
          if (size < 1 || size > 100) throw Error('Unbounded administration detail');
          const resources = Array.from({length: 41}, (_, i) => ({id: row.id + '-' + i, resourceName: '待激活资源' + i,
            resourceCode: 'resource.' + i, durationDays: 30, currentState: 'NOT_STARTED'}));
          respond({content: resources.slice(number * size, (number + 1) * size), page: {number, size, totalElements: 41, totalPages: Math.ceil(41 / size)}}); return true;
        }
        respond(row); return true;
      }
      if (req.method !== 'POST') throw Error('Unexpected administration mutation');
      let raw = ''; for await (const chunk of req) raw += chunk;
      const data = JSON.parse(raw); writes.push({tenant, suffix, data});
      await wait(450);
      if (!suffix) {
        if (!data.allocationId || !packages.some(pkg => pkg.code === data.packageCode)) throw Error('Allocation lost its command');
        const key = tenant + ':' + data.allocationId;
        let row = receipts.get(key);
        if (row && row.packageCode !== data.packageCode) throw Error('Retry changed the allocation');
        if (!row) {
          row = {id: 'instance-' + receipts.size, packageCode: data.packageCode, packageName: packages.find(pkg => pkg.code === data.packageCode).name,
            state: 'PENDING', durationDays: 30, assignedAt: '2026-10-23T08:00:00Z', acquisition: 'ASSIGNED', resourceCount: 41,
            allowedActions: ['resources', 'revoke']};
          receipts.set(key, row); rows.unshift(row);
        }
        if (fail) {fail = false; expectedFailure(503); respond({message: '分配结果未确认（测试）'}, 503); return true;}
        respond(row); return true;
      }
      const row = rows.find(row => suffix === row.id + '/revoke');
      if (!row) throw Error('Unexpected revoke target');
      row.state = 'REVOKED'; row.allowedActions = ['resources']; respond({instanceId: row.id}); return true;
    },
  };
}
