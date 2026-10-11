import {readFileSync} from 'node:fs';
import {compileLocalQuery} from '../libs/components/DataTable/localQuery.ts';

const parentBase = '/common/platform/dictionaries', itemBase = '/common/platform/dictionary-items';
const parentSchema = JSON.parse(readFileSync(new URL('./common-main-schemas/Dictionary.json', import.meta.url), 'utf8'));
const itemSchema = JSON.parse(readFileSync(new URL('./common-main-schemas/DictionaryItem.json', import.meta.url), 'utf8'));
const tenantSchema = JSON.parse(readFileSync(new URL('./tenant-readonly-owner-schema.fixture.json', import.meta.url), 'utf8'));
const fields = properties => Object.fromEntries(Object.entries(properties).map(([key, value]) => [key,
  {type: value.type === 'boolean' ? 'boolean' : value.type === 'integer' ? 'integer' : 'string'}]));
const page = (rows, schema, url) => {
  const number = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
  if (!Number.isInteger(number) || number < 0 || !Number.isInteger(size) || size < 1 || size > 100) throw Error('Configuration reads require bounded real pages');
  const query = compileLocalQuery(fields(schema.schema.properties), {filters: Object.fromEntries([...url.searchParams]
    .filter(([key])=>!['page', 'size', 'sort', 'parentDictionaryCode'].includes(key))), sort: url.searchParams.getAll('sort')});
  const matches = rows.filter(query.matches).sort(query.compare);
  return {content: matches.slice(number * size, (number + 1) * size), page: {number, size, totalElements: matches.length, totalPages: Math.ceil(matches.length / size)}};
};

/** Actual exported schema contracts with isolated rows and writes; never real dictionary mutations. */
export function createDictionaryLifecycleFixture() {
  const parents = ['alpha', 'beta'].map(code=>({id: 'dictionary-' + code, code, name: 'Dictionary ' + code, enabled: true, sort: 0}));
  const rows = parents.flatMap(parent=>Array.from({length: 521}, (_, index)=>({id: parent.code + '-item-' + index,
    dictionaryCode: parent.code, name: parent.code + ' item ' + String(index).padStart(3, '0'), value: 'value-' + index,
    i18nKey: null, enabled: true, sort: index, description: 'Actual item'})));
  const reads = [], writes = [];
  const tenant = {id: 'owner-readonly-tenant', name: 'Actual readonly owner tenant', ownerId: 'owner-real-id',
    ownerName: 'Actual owner', tenantType: 'ORGANIZATION', description: 'Actual metadata'};
  let active = false, mode = 'all', failure, sequence = 0;
  return {parents, rows, reads, writes, tenant, enable() {active = true;}, mode(value) {mode = value;}, failWrite(method) {failure = method;},
    async handle(req, url, respond, expectedFailure) {
      const path = url.pathname;
      if (active && path === parentBase + '/options' && url.searchParams.get('dictionaryCode') === 'tenant.type') {
        return respond([{value: 'PERSONAL', label: '个人'}, {value: 'ORGANIZATION', label: '组织'}]), true;
      }
      if (active && ['/common/platform/tenants', '/common/platform/tenants/schema', '/common/users/picker/selected'].includes(path)) {
        reads.push({path, params: new URLSearchParams(url.searchParams), mode});
        if (path.endsWith('/schema')) return respond(tenantSchema), true;
        if (path.endsWith('/selected')) return respond([{id: tenant.ownerId, name: tenant.ownerName, email: 'owner@example.test'}]), true;
        if (req.method === 'GET') return respond({content: [tenant], page: {number: 0, size: 20, totalElements: 1, totalPages: 1}}), true;
        if (req.method !== 'PUT') throw Error('Readonly owner fixture only grants tenant metadata editing');
        let raw = ''; for await (const chunk of req) raw += chunk;
        const data = JSON.parse(raw); if (data.id !== tenant.id || 'ownerId' in data) throw Error('Metadata editing must omit the readonly owner; the real service preserves it');
        writes.push({path, method: req.method, data, mode, completed: true}); Object.assign(tenant, data); return respond(tenant), true;
      }
      if (!active || ![parentBase, parentBase + '/schema', itemBase, itemBase + '/schema'].includes(path)) return false;
      const currentMode = mode, isItem = path.startsWith(itemBase), schema = isItem ? itemSchema : parentSchema;
      if (req.method === 'GET') {
        reads.push({path, params: new URLSearchParams(url.searchParams), mode: currentMode});
        if (path.endsWith('/schema')) {
          if (mode === 'denied' && !isItem || mode === 'child-denied' && isItem) {
            expectedFailure(503); return respond({message: '字典权限结构加载失败（测试）'}, 503), true;
          }
          const result = structuredClone(schema);
          if (mode === 'view') result.buttons = [];
          if (mode === 'config') result.buttons = isItem ? [] : result.buttons.filter(button=>button.key === 'config.item');
          if (mode === 'readonly-code' && !isItem) Object.assign(result.schema.properties.code, {readOnly: true, 'x-field-access': 'VISIBLE'});
          if (mode === 'masked-code' && !isItem) Object.assign(result.schema.properties.code, {readOnly: true, 'x-field-access': 'MASKED'});
          return respond(result), true;
        }
        if (isItem) {
          const parent = url.searchParams.get('parentDictionaryCode');
          if (!parents.some(row=>row.code === parent)) throw Error('Child query lost its explicit captured dictionary');
          return respond(page(rows.filter(row=>row.dictionaryCode === parent), schema, url)), true;
        }
        return respond(page(parents.map(row=>({...row, ...(mode === 'masked-code' ? {code: '***'} : {})})), schema, url)), true;
      }
      if (!isItem || !['all', 'readonly-code'].includes(currentMode)) throw Error('Configuration/view permission does not authorize dictionary CRUD');
      let raw = ''; for await (const chunk of req) raw += chunk;
      const data = raw ? JSON.parse(raw) : undefined;
      const write = {path, method: req.method, data, params: new URLSearchParams(url.searchParams), mode: currentMode, completed: false};
      writes.push(write); const fail = failure === req.method; if (fail) failure = undefined;
      await new Promise(resolve=>setTimeout(resolve, 450)); write.completed = true;
      if (fail) {expectedFailure(503); return respond({message: '字典项保存失败（测试）'}, 503), true;}
      if (req.method === 'DELETE') {
        const ids = url.searchParams.get('ids')?.split(',') ?? [];
        for (const id of ids) {const index = rows.findIndex(row=>row.id === id); if (index < 0) throw Error('Deletion lost its captured item'); rows.splice(index, 1);}
        return respond(ids), true;
      }
      if (!data?.name?.trim() || !data?.value?.trim() || !parents.some(row=>row.code === data.dictionaryCode)) throw Error('Invalid or unscoped item submission');
      if (req.method === 'PUT') {
        const current = rows.find(row=>row.id === data.id);
        if (!current || current.dictionaryCode !== data.dictionaryCode) throw Error('Nested editing attempted a dictionary or record move');
        Object.assign(current, data); return respond(current), true;
      }
      if (req.method !== 'POST') throw Error('Unexpected configuration write');
      const created = {...data, id: data.dictionaryCode + '-created-' + ++sequence}; rows.push(created);
      return respond(created), true;
    }};
}
