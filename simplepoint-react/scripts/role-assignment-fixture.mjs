import {readFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
import {compileLocalQuery} from '../libs/components/DataTable/localQuery.ts';

// Exported and compared with the real HTTP schema by UserRoleAssignmentHttpTest.
const schema = JSON.parse(readFileSync(new URL('./role-assignment-schema.fixture.json', import.meta.url), 'utf8'));

export function createRoleAssignmentFixture() {
  const rows = Array.from({length: 240}, (_, i) => ({id: `role-${String(i).padStart(3, '0')}`,
    name: `Candidate${String(i).padStart(3, '0')}`, authority: `ROLE_${i}`, description: `Description ${i}`}));
  const grants = new Map([['member-144', new Set(rows.slice(0, 120).map(row => row.id))], ['member-143', new Set(['role-001'])]]);
  const fields = Object.fromEntries(['id', 'name', 'authority', 'description'].map(key => [key, {type: 'string'}]));
  const moves = []; let fail = false;
  return {
    grants, moves, failMove() {fail = true;},
    async handle(req, url, respond, expectedFailure) {
      if (!url.pathname.startsWith('/common/users/')) return false;
      const base = '/common/users/role-candidates';
      if (url.pathname === base || url.pathname === `${base}/schema`) {
        const parent = url.searchParams.get('userId');
        if (!grants.has(parent)) throw Error('Role candidate request omitted or reused the target member');
        if (url.pathname.endsWith('/schema')) return respond(schema), true;
        const page = Number(url.searchParams.get('page') ?? 0);
        const size = Number(url.searchParams.get('size') ?? 20);
        if (size > 100) throw Error('Unbounded role assignment query');
        const filters = Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort', 'userId', 'selected'].includes(key)));
        const query = compileLocalQuery(fields, {filters, sort: url.searchParams.getAll('sort')});
        const selected = url.searchParams.get('selected') === 'true';
        const result = rows.filter(row => grants.get(parent).has(row.id) === selected && query.matches(row)).sort(query.compare);
        return respond({content: result.slice(page*size, (page+1)*size), page: {number: page, size,
          totalElements: result.length, totalPages: Math.ceil(result.length/size)}}), true;
      }
      if (['/common/users/authorize', '/common/users/unauthorized'].includes(url.pathname)) {
        const chunks = []; for await (const chunk of req) chunks.push(chunk);
        const body = JSON.parse(Buffer.concat(chunks).toString()); moves.push(body);
        if (!grants.has(body.userId) || body.roleIds.length > 100) throw Error('Invalid role assignment mutation');
        await delay(250);
        if (fail) {fail = false; expectedFailure(503); return respond({message: '角色分配失败（测试）'}, 503), true;}
        for (const id of body.roleIds) {
          if (url.pathname.endsWith('/authorize')) grants.get(body.userId).add(id); else grants.get(body.userId).delete(id);
        }
        return respond([]), true;
      }
      if (url.pathname === '/common/users/authorized') throw Error('Role assignment must not hydrate all assigned IDs');
      return false;
    },
  };
}
