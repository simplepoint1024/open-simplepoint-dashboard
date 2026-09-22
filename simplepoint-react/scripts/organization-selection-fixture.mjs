import {readFileSync} from 'node:fs';
import {compileLocalQuery} from '../libs/components/DataTable/localQuery.ts';

const schema = JSON.parse(readFileSync(new URL('./organization-selection-schema.fixture.json', import.meta.url), 'utf8'));
export const organizationSelectionRoute = {id: 'org-selection-test', code: 'common.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/org-selection', component: 'common/system/DataScope', label: '组织选择测试', scopeTypes: ['PLATFORM']};

export function createOrganizationSelectionFixture() {
  const rows = Array.from({length: 240}, (_, i) => ({id: `org-${String(i).padStart(3, '0')}`, name: `Organization${String(i).padStart(3, '0')}`,
    code: `code-${i}`, type: 'DEPT', description: `Description ${i}`, enabled: i !== 239}));
  const initialIds = rows.slice(0, 120).map(row => row.id);
  const scopes = [{id: 'scope-a', name: 'Selection A', type: 'CUSTOM', customDeptIds: [...initialIds]},
    {id: 'scope-b', name: 'Selection B', type: 'CUSTOM', customDeptIds: ['org-001', 'org-239']},
    {id: 'scope-large', name: 'Selection Large', type: 'CUSTOM', customDeptIds: Array.from({length: 2001}, (_, i) => `legacy-${i}`)}];
  const fields = {id: {type: 'string'}, name: {type: 'string'}, code: {type: 'string'}, type: {type: 'string'},
    description: {type: 'string'}, enabled: {type: 'boolean'}};
  let fail = false, readonly = false;
  const queries = [], writes = [];
  return {
    queries, writes, scopes, initialIds, failNext() {fail = true;}, readonly() {readonly = true;},
    async handle(req, url, respond, expectedFailure) {
      const base = '/common/platform/organizations/selection';
      if (url.pathname === '/common/data-scopes/schema') return respond({buttons: [
        {key: 'edit', title: '编辑', icon: 'EditOutlined', argumentMinSize: 1, argumentMaxSize: 1, sort: 0},
      ], schema: {type: 'object', properties: {
        id: {type: 'string', title: 'ID', readOnly: true, 'x-list-visible': true},
        name: {type: 'string', title: '名称', 'x-list-visible': true},
        type: {type: 'string', title: '范围', enum: ['ALL', 'CUSTOM', 'SELF'], 'x-list-visible': true},
        customDeptIds: {type: 'array', title: '组织选择', items: {type: 'string'}, readOnly: readonly},
      }}}), true;
      if (url.pathname === '/common/data-scopes' && req.method === 'GET') return respond({content: scopes,
        page: {number: 0, size: 20, totalElements: scopes.length, totalPages: 1}}), true;
      if (url.pathname === '/common/data-scopes' && req.method === 'PUT') {
        const chunks = []; for await (const chunk of req) chunks.push(chunk);
        const data = JSON.parse(Buffer.concat(chunks).toString()); writes.push(data);
        Object.assign(scopes.find(row => row.id === data.id), data); return respond(data), true;
      }
      if (url.pathname === `${base}/schema`) return respond(schema), true;
      if (url.pathname !== base) return false;
      if (req.method !== 'POST') throw Error('Organization selection uses a read-only POST, not an unbounded URL');
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const draft = JSON.parse(Buffer.concat(chunks).toString()); queries.push(draft);
      if (!Array.isArray(draft.ids) || draft.ids.length > 2000) throw Error('Unbounded organization draft');
      if (fail) {fail = false; expectedFailure(503); return respond({message: '组织草稿查询失败（测试）'}, 503), true;}
      const page = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
      if (size > 100) throw Error('Unbounded organization page');
      const query = compileLocalQuery(fields, {filters: draft.filters, sort: url.searchParams.getAll('sort')});
      const ids = new Set(draft.ids);
      const result = rows.filter(row => ids.has(row.id) === draft.selected && query.matches(row)).sort(query.compare);
      return respond({content: result.slice(page*size, (page+1)*size), page: {number: page, size,
        totalElements: result.length, totalPages: Math.ceil(result.length/size)}}), true;
    },
  };
}
