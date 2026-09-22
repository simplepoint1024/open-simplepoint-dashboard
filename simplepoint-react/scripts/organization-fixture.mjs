import {setTimeout as delay} from 'node:timers/promises';
import {compileLocalQuery, localQuerySchema} from '../libs/components/DataTable/localQuery.ts';

export const organizationRoute = {id: 'organization-test', code: 'common.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/organizations', component: 'common/platform/Organization', label: '组织树测试', scopeTypes: ['PLATFORM']};

export function createOrganizationFixture() {
  const fields = {name: {type: 'string', label: '组织名称'}, code: {type: 'string'}, sort: {type: 'integer'}, parentId: {type: 'string'}};
  const roots = Array.from({length: 120}, (_, i) => ({id: `root-${i}`, name: `root-${String(i).padStart(3, '0')}`,
    code: `root-${i}`, sort: i, parentId: null, hasChildren: i === 0}));
  const children = Array.from({length: 125}, (_, i) => ({id: `child-${i}`, name: `child-${String(i).padStart(3, '0')}`,
    code: `child-${i}`, sort: i, parentId: 'root-0', hasChildren: false}));
  let fail = true;
  return {
    async handle(req, url, respond, expectedFailure) {
      const base = '/common/platform/organizations';
      if (url.pathname === `${base}/schema`) return respond({schema: localQuerySchema(fields), buttons: []}), true;
      if (![base, `${base}/tree`].includes(url.pathname)) return false;
      const page = Number(url.searchParams.get('page') ?? 0);
      const size = Number(url.searchParams.get('size') ?? 20);
      const parent = url.searchParams.get('treeParentId');
      if (parent && page === 1 && fail) {
        fail = false;
        await delay(250); expectedFailure(503);
        return respond({message: '组织子节点测试失败'}, 503), true;
      }
      const query = compileLocalQuery(fields, {
        filters: Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort', 'treeParentId'].includes(key))),
        sort: url.searchParams.getAll('sort'),
      });
      const rows = (url.pathname.endsWith('/tree') ? parent ? children.filter(row => row.parentId === parent) : roots : [...roots, ...children])
        .filter(query.matches).sort(query.compare);
      return respond({content: rows.slice(page * size, (page + 1) * size),
        page: {number: page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}}), true;
    },
  };
}
