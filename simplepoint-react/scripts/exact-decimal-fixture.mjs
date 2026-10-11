import {readFileSync} from 'node:fs';
import {compileLocalQuery} from '../libs/components/DataTable/localQuery.ts';

const schemas = Object.fromEntries(['all', 'masked'].map(mode => [mode, JSON.parse(readFileSync(new URL(
  mode === 'all' ? './exact-decimal-package-schema.fixture.json' : './exact-decimal-package-masked-schema.fixture.json', import.meta.url), 'utf8'))]));
const base = '/common/platform/packages';
export const exactDecimalRoute = {id: 'exact-decimal-package', code: 'common.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/exact-decimal-package', component: 'common/platform/Package', label: '精确金额测试', scopeTypes: ['PLATFORM']};

/** Actual HTTP-exported contracts and production UI; all network writes are isolated. */
export function createExactDecimalFixture() {
  const rows = ['99999999999999999.99', '99999999999999999.98', '0.00', null].map((price, index) => ({
    id: `exact-package-${index}`, name: `Exact Package ${index}`, code: `exact-package-${index}`, price,
    durationDays: 365, sort: index, enabled: true, description: 'Original exact package metadata',
  }));
  const reads = [], writes = []; let enabled = false, mode = 'all';
  return {rows, reads, writes, enable() {enabled = true;}, mode(value) {mode = value;},
    async handle(req, url, respond) {
      if (!enabled || ![base, base + '/schema'].includes(url.pathname)) return false;
      const actual = schemas[mode];
      if (url.pathname === base + '/schema') {respond(actual); return true;}
      if (req.method === 'GET') {
        const params = new URLSearchParams(url.searchParams); reads.push({params, mode});
        const page = Number(params.get('page') ?? 0), size = Number(params.get('size') ?? 20);
        if (!Number.isSafeInteger(page) || page < 0 || !Number.isSafeInteger(size) || size < 1 || size > 20) throw Error('Decimal list lost bounded pagination');
        const fields = Object.fromEntries(Object.entries(actual.schema['x-query'].fields).map(([key, field]) => [key,
          {type: field.javaType === 'BigDecimal' ? 'number' : field.javaType === 'Integer' ? 'integer' : field.javaType === 'Boolean' ? 'boolean' : 'string'}]));
        const query = compileLocalQuery(fields, {filters: Object.fromEntries([...params].filter(([key]) => !['page', 'size', 'sort'].includes(key))), sort: params.getAll('sort')});
        const selected = rows.filter(query.matches).sort(query.compare);
        const content = selected.slice(page * size, (page + 1) * size).map(row => mode === 'masked' ? {...row, price: '***'} : row);
        respond({content, page: {number: page, size, totalElements: selected.length, totalPages: Math.ceil(selected.length / size)}}); return true;
      }
      if (req.method === 'PUT') {
        let raw = ''; for await (const chunk of req) raw += chunk; const data = JSON.parse(raw);
        if (mode === 'masked' && 'price' in data) throw Error('Default CRUD resubmitted a protected decimal');
        if ('price' in data && data.price !== null && typeof data.price !== 'string') throw Error('Default CRUD coerced decimal text to Number');
        const row = rows.find(row => row.id === data.id); if (!row) throw Error('Default decimal edit lost captured identity');
        writes.push({data, mode}); Object.assign(row, data); respond(row); return true;
      }
      throw Error('Unexpected decimal fixture method ' + req.method);
    },
  };
}
