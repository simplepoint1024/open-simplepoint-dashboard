import {readFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
import {compileLocalQuery, localQuerySchema} from '../libs/components/DataTable/localQuery.ts';

const schema = JSON.parse(readFileSync(new URL('./user-assignment-schema.fixture.json', import.meta.url), 'utf8'));
export const tenantAssignmentRoute = {id: 'tenant-assignment-test', code: 'common.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/tenant-assignments', component: 'common/platform/Tenant', label: '租户成员分配测试', scopeTypes: ['PLATFORM']};

export function createUserAssignmentFixture() {
  const fields = {id: {type: 'string'}, name: {type: 'string'}, email: {type: 'string'}, phoneNumber: {type: 'string'}};
  const rows = Array.from({length: 240}, (_, i) => ({id: `user-${String(i).padStart(3, '0')}`,
    name: `Candidate${String(i).padStart(3, '0')}`, email: `user${i}@example.test`, phoneNumber: `138${String(i).padStart(8, '0')}`}));
  const tenants = [{id: 'tenant-a', name: 'Tenant A', ownerId: 'user-000'}, {id: 'tenant-b', name: 'Tenant B', ownerId: 'user-001'}];
  const grants = new Map([['tenant-a', new Set(rows.slice(0, 120).map(row => row.id))], ['tenant-b', new Set(['user-001'])]]);
  let fail = false;
  const moves = [];
  return {
    grants, moves,
    failMove() {fail = true;},
    async handle(req, url, respond, expectedFailure) {
      const base = '/common/platform/tenants';
      if (url.pathname === `${base}/schema`) return respond({schema: localQuerySchema({name: {type: 'string', label: '租户名称'}}), buttons: []}), true;
      if (url.pathname === base) return respond({content: tenants, page: {number: 0, size: 20, totalElements: 2, totalPages: 1}}), true;
      if (!url.pathname.startsWith(`${base}/users/`)) return false;
      const parent = url.searchParams.get('tenantId');
      if (url.pathname.endsWith('/items/schema')) {
        if (!grants.has(parent)) throw Error('Assignment schema request omitted the target tenant');
        return respond(schema), true;
      }
      if (url.pathname.endsWith('/items')) {
        if (!grants.has(parent)) throw Error('Unknown assignment tenant');
        const page = Number(url.searchParams.get('page') ?? 0);
        const size = Number(url.searchParams.get('size') ?? 20);
        if (size > 100) throw Error('Unbounded member assignment query');
        const filters = Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort', 'tenantId', 'selected'].includes(key)));
        const query = compileLocalQuery(fields, {filters, sort: url.searchParams.getAll('sort')});
        const selected = url.searchParams.get('selected') === 'true';
        const result = rows.filter(row => grants.get(parent).has(row.id) === selected && query.matches(row)).sort(query.compare);
        return respond({content: result.slice(page*size, (page+1)*size), page: {number: page, size,
          totalElements: result.length, totalPages: Math.ceil(result.length/size)}}), true;
      }
      if (['/authorize', '/unauthorized'].some(path => url.pathname.endsWith(path))) {
        const chunks = []; for await (const chunk of req) chunks.push(chunk);
        const body = JSON.parse(Buffer.concat(chunks).toString()); moves.push(body);
        await delay(250);
        if (fail) {fail = false; expectedFailure(503); return respond({message: '成员分配失败（测试）'}, 503), true;}
        const tenant = tenants.find(row => row.id === body.tenantId);
        if (!tenant || body.userIds.includes(tenant.ownerId)) throw Error('Protected owner must never be moved');
        for (const id of body.userIds) {
          if (url.pathname.endsWith('/authorize')) grants.get(tenant.id).add(id); else grants.get(tenant.id).delete(id);
        }
        return respond([]), true;
      }
      throw Error('Unexpected legacy/full assignment request: '+url.pathname);
    },
  };
}
