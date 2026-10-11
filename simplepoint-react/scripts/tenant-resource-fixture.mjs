import {setTimeout as delay} from 'node:timers/promises';
import {readFileSync} from 'node:fs';
import {fixtureSchema, fixturePage} from './query-fixture.mjs';
const fields = {name: {type: 'string'}, code: {type: 'string'}, type: {type: 'string'},
  origin: {type: 'string', enum: ['PLATFORM', 'TENANT']}, enabled: {type: 'boolean'}, sort: {type: 'integer'}};
export const tenantResourceRoutes = JSON.parse(readFileSync(new URL('./tenant-resource-contracts/navigation.json', import.meta.url), 'utf8'));
export function createTenantResourceFixture() {
  const tenantId = 'tenant-resource-test', rows = Array.from({length: 521}, (_, i) => ({id: `real-resource-${i}`, platformResourceId: `real-resource-${i}`,
    code: `platform.page.${i}`, name: `Target${String(i).padStart(3, '0')}`, type: 'PAGE', grantable: true, enabled: true,
    parentId: null, path: `/real/${i}`, sort: i}));
  let active = false, fail = false;
  const profile = {id: tenantId, name: 'Workspace test', description: 'Actual test workspace', ownerId: 'smoke', profileEditable: true,
    tenantType: 'ORGANIZATION', resourceMode: 'CUSTOM'};
  const state = {tenantId, rows, profile, writes: [], requests: [], profileWrites: [],
    enable() {active = true;}, disable() {active = false;}, isActive() {return active;}, failWrite() {fail = true;},
    async handle(req, url, respond, expectedFailure) {
      if (!active) return false;
      if (url.pathname === '/common/tenants/current') {
        respond([{tenantId: '__platform__', tenantName: '平台', tenantType: 'PLATFORM'},
          {tenantId, tenantName: profile.name, tenantType: 'ORGANIZATION'}]); return true;
      }
      const base = '/common/tenants/current-resources';
      if (!url.pathname.startsWith(base) && url.pathname !== '/common/tenants/current-profile') return false;
      if (req.headers['x-tenant-id'] !== tenantId) throw Error('Tenant resource request lost its captured workspace');
      state.requests.push({path: url.pathname, query: new URLSearchParams(url.search), method: req.method});
      if (url.pathname === base + '/schema') return respond(fixtureSchema(fields)), true;
      let body;
      if (['POST', 'PUT'].includes(req.method)) {
        const chunks = []; for await (const chunk of req) chunks.push(chunk);
        body = JSON.parse(Buffer.concat(chunks).toString() || '{}');
      }
      if (url.pathname === '/common/tenants/current-profile') {
        if (req.method === 'PUT') {
          state.profileWrites.push(body); await delay(250);
          if (fail) {fail = false; expectedFailure(503); respond({message: '租户资料保存失败（测试）'}, 503); return true;}
          Object.assign(profile, body);
        }
        respond(profile); return true;
      }
      if (url.pathname === base + '/sync/preview') {await delay(200); respond({added: 1, refreshed: 2, deactivated: 0, additions: [], deactivations: []}); return true;}
      if (req.method !== 'GET') {
        state.writes.push({path: url.pathname, query: new URLSearchParams(url.search), body, method: req.method});
        await delay(250);
        if (fail) {fail = false; expectedFailure(503); respond({message: '租户资源保存失败（测试）'}, 503); return true;}
        if (url.pathname.endsWith('/mode')) {profile.resourceMode = body.mode; respond(body.mode); return true;}
        if (url.pathname.endsWith('/sync')) {respond({added: 1, refreshed: 2, deactivated: 0}); return true;}
        const id = url.pathname.slice(base.length + 1), existing = rows.find(row => row.id === id);
        if (req.method === 'DELETE') rows.splice(rows.indexOf(existing), 1);
        else if (existing) Object.assign(existing, body);
        else rows.push({id: 'tenant-created', code: 'tenant.created', grantable: body.type === 'PAGE', ...body});
        respond(existing ?? rows.at(-1)); return true;
      }
      let result = rows;
      if (url.pathname.endsWith('/picker')) {
        const kind = url.searchParams.get('kind'), childType = url.searchParams.get('childType');
        result = rows.filter(row => row.enabled && row.id !== url.searchParams.get('excludedId')
          && (kind === 'PAGE_TARGET' ? row.type === 'PAGE' && row.grantable && row.platformResourceId
            : !['ACTION', 'API'].includes(row.type) && (!['GROUP', 'MODULE', 'PAGE'].includes(childType) || row.type !== 'FEATURE')));
      } else if (url.pathname !== base) {
        respond(rows.find(row => row.id === url.pathname.slice(base.length + 1))); return true;
      } else if (url.searchParams.has('parentId')) result = rows.filter(row => row.parentId === url.searchParams.get('parentId'));
      else if (url.searchParams.get('rootsOnly') === 'true') result = rows.filter(row => !row.parentId);
      const selected = url.searchParams.get('selectedId'), keyword = (url.searchParams.get('keyword') ?? '').toLowerCase();
      result = result.filter(row => (!selected || row.id === selected) && (!keyword || row.name.toLowerCase().includes(keyword) || row.code.includes(keyword)));
      if (url.pathname === base) return respond(fixturePage(result.map(row => ({...row, origin: row.platformResourceId ? 'PLATFORM' : 'TENANT'})),
        fields, url, ['parentId', 'rootsOnly', 'keyword'])), true;
      const page = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
      if (size > 20) throw Error('Unbounded tenant resource browser read');
      respond({content: result.slice(page * size, (page + 1) * size), page: {number: page, size, totalElements: result.length, totalPages: Math.ceil(result.length / size)}}); return true;
    },
  };
  return state;
}
