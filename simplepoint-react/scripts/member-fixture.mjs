import {readFileSync} from 'node:fs';
import {compileLocalQuery} from '../libs/components/DataTable/localQuery.ts';

// Contract is generated and compared by TenantMemberQueryHttpTest, not invented by this mock.
const schema = JSON.parse(readFileSync(new URL('./member-schema.fixture.json', import.meta.url), 'utf8'));
export const memberRoute = {id: 'member-test', code: 'common.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/members', component: 'common/system/User', label: '成员查询测试', scopeTypes: ['PLATFORM']};

export function createMemberFixture() {
  const fields = {userId: {type: 'string'}, name: {type: 'string'}, email: {type: 'string'},
    orgId: {type: 'string'}, orgName: {type: 'string'}, enabled: {type: 'boolean'}};
  const rows = Array.from({length: 145}, (_, i) => ({userId: `member-${i}`, name: `Member${String(i).padStart(3, '0')}`,
    email: `member${i}@example.test`, orgId: `org-${i % 2}`, orgName: i % 2 ? 'Operations' : 'Engineering',
    enabled: i % 2 === 0, revision: 0}));
  let fail = false;
  let reorder = false;
  let reorderedSchemas = 0;
  return {
    failNext() {fail = true;},
    reorderSchema() {reorder = true;},
    get reorderedSchemas() {return reorderedSchemas;},
    async handle(req, url, respond, expectedFailure) {
      const base = '/common/tenant-members';
      if (url.pathname === '/common/platform/dictionaries/options'
          && url.searchParams.get('dictionaryCode') === 'tenant.member.user-type') {
        return respond(['INTERNAL', 'EXTERNAL', 'REFERENCE'].map(value => ({value,
          label: `i18n:tenant-members.userType.${value}`}))), true;
      }
      if (url.pathname === `${base}/by-organization` || url.pathname === `${base}/organization-candidates`) {
        const candidate = url.pathname.endsWith('organization-candidates');
        const source = candidate ? rows : Array.from({length: 125}, (_, i) => ({userId: `org-user-${i}`,
          name: `OrgUser${String(i).padStart(3, '0')}`, email: `org${i}@example.test`,
          orgId: i % 2 ? `child-${i}` : 'root-0', orgName: i % 2 ? 'Child' : 'Root',
          enabled: true, userType: 'INTERNAL', revision: 0}));
        const keyword = (url.searchParams.get('keyword') ?? '').toLowerCase();
        const matches = source.filter(row => `${row.name} ${row.email} ${row.userId}`.toLowerCase().includes(keyword));
        const page = Number(url.searchParams.get('page') ?? 0);
        const size = Number(url.searchParams.get('size') ?? 20);
        if (size > 100) throw Error('Organization member fixture refuses unbounded pages');
        return respond({content: matches.slice(page * size, (page + 1) * size),
          page: {number: page, size, totalElements: matches.length,
            totalPages: Math.ceil(matches.length / size)}}), true;
      }
      if (url.pathname === `${base}/schema`) {
        const current = structuredClone(schema);
        if (reorder) {
          current.schema['x-query'] = Object.fromEntries(Object.entries(current.schema['x-query']).reverse());
          reorderedSchemas++;
        }
        return respond(current), true;
      }
      if (url.pathname === `${base}/capabilities`) return respond({manage: true}), true;
      if (url.pathname !== base || req.method !== 'GET') return false;
      if (fail) {
        fail = false; expectedFailure(400);
        return respond({code: 'INVALID_QUERY', message: '成员查询条件已失效（测试）'}, 400), true;
      }
      const page = Number(url.searchParams.get('page') ?? 0);
      const size = Number(url.searchParams.get('size') ?? 20);
      if (size > 200) throw Error('Member fixture refuses an unbounded page');
      const query = compileLocalQuery(fields, {
        filters: Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort'].includes(key))),
        sort: url.searchParams.getAll('sort'),
      });
      const matches = rows.filter(query.matches).sort(query.compare);
      return respond({content: matches.slice(page * size, (page + 1) * size),
        page: {number: page, size, totalElements: matches.length, totalPages: Math.ceil(matches.length / size)}}), true;
    },
  };
}
