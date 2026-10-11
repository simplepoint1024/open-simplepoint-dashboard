import {readFileSync} from 'node:fs';
import {compileLocalQuery} from '../libs/components/DataTable/localQuery.ts';

const tenantBase = '/common/platform/tenants', packageBase = '/common/platform/packages', applicationBase = '/common/platform/applications';
const readSchema = name => JSON.parse(readFileSync(new URL('./configuration-' + name + '-schema.fixture.json', import.meta.url), 'utf8'));
const tenantSchema = readSchema('tenant'), packageSchema = readSchema('package'), applicationSchema = readSchema('application');
const memberSchema = JSON.parse(readFileSync(new URL('./user-assignment-schema.fixture.json', import.meta.url), 'utf8'));
const fields = schema => Object.fromEntries(Object.entries(schema.schema['x-query'].fields).map(([name, field])=>[name,
  {type: ['Boolean', 'boolean'].includes(field.javaType) ? 'boolean' : ['Integer', 'Long', 'int', 'long'].includes(field.javaType) ? 'integer' : 'string'}]));
const page = (rows, schema, url) => {
  const number = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
  if (!Number.isSafeInteger(number) || number < 0 || !Number.isSafeInteger(size) || size < 1 || size > 100) throw Error('Configuration lost bounded server paging');
  const query = compileLocalQuery(fields(schema), {filters: Object.fromEntries([...url.searchParams]
    .filter(([name])=>!['page', 'size', 'sort', 'selected', 'tenantId', 'packageCode'].includes(name))), sort: url.searchParams.getAll('sort')});
  const matches = rows.filter(query.matches).sort(query.compare);
  return {content: matches.slice(number * size, (number + 1) * size), page: {number, size, totalElements: matches.length, totalPages: Math.ceil(matches.length / size)}};
};
const wait = milliseconds => new Promise(resolve=>setTimeout(resolve, milliseconds));

/** Generated HTTP schemas and isolated assignments; never writes real tenants, packages or accounts. */
export function createConfigurationLifecycleFixture() {
  const members = Array.from({length: 521}, (_, index)=>({id: 'member-' + String(index).padStart(3, '0'), name: 'Member' + String(index).padStart(3, '0'),
    email: 'member' + index + '@example.test', phoneNumber: '138' + String(index).padStart(8, '0')}));
  const packages = Array.from({length: 521}, (_, index)=>({id: 'package-' + index, code: 'package-' + String(index).padStart(3, '0'),
    name: 'Package' + String(index).padStart(3, '0'), description: 'Actual package', enabled: true, sort: index}));
  const applications = Array.from({length: 521}, (_, index)=>({id: 'application-' + index, code: 'application-' + String(index).padStart(3, '0'),
    name: 'Application' + String(index).padStart(3, '0'), description: 'Actual application', enabled: true, sort: index}));
  const tenants = [{id: 'configuration-tenant-a', name: 'Tenant Alpha', ownerId: members[0].id, ownerName: members[0].name, tenantType: 'ORGANIZATION'},
    {id: 'configuration-tenant-b', name: 'Tenant Beta', ownerId: members[1].id, ownerName: members[1].name, tenantType: 'ORGANIZATION'}];
  const parentPackages = [{id: 'configuration-package-a', code: 'parent-a', name: 'Parent Alpha', enabled: true, price: '0.00'},
    {id: 'configuration-package-b', code: 'parent-b', name: 'Parent Beta', enabled: true, price: '0.00'}];
  const memberGrants = new Map([[tenants[0].id, new Set(members.slice(0, 120).map(row=>row.id))], [tenants[1].id, new Set([members[1].id])]]);
  const packageGrants = new Map([[tenants[0].id, new Set(packages.slice(0, 120).map(row=>row.code))], [tenants[1].id, new Set([packages[1].code])]]);
  const applicationGrants = new Map([['parent-a', new Set(applications.slice(0, 120).map(row=>row.code))], ['parent-b', new Set([applications[1].code])]]);
  const reads = [], writes = [];
  let active = false, mode = 'all', failure, capabilityFailure = false;
  return {tenants, parentPackages, members, packages, applications, memberGrants, packageGrants, applicationGrants, reads, writes,
    enable() {active = true;}, mode(next) {mode = next;}, failWrite() {failure = true;}, failCapability() {capabilityFailure = true;},
    async handle(req, url, respond, expectedFailure) {
      if (!active) return false;
      const path = url.pathname, currentMode = mode;
      if (path === '/common/platform/dictionaries/options' && url.searchParams.get('dictionaryCode') === 'tenant.type') {
        respond([{value: 'PERSONAL', label: '个人'}, {value: 'ORGANIZATION', label: '组织'}]); return true;
      }
      if (req.method === 'GET' && [tenantBase, tenantBase + '/schema', packageBase, packageBase + '/schema', tenantBase + '/users/capabilities'].includes(path)) {
        reads.push({path, mode: currentMode, params: new URLSearchParams(url.searchParams)});
        if (path.endsWith('/capabilities')) {
          const id = url.searchParams.get('tenantId');
          if (!tenants.some(row=>row.id === id)) throw Error('Capability lost its selected tenant');
          if (capabilityFailure) {capabilityFailure = false; expectedFailure(503); respond({message: '租户配置能力读取失败（测试）'}, 503); return true;}
          respond({canConfigureMembers: !['viewer', 'personal'].includes(mode)}); return true;
        }
        if (path.endsWith('/schema')) {
          const result = structuredClone(path.startsWith(packageBase) ? packageSchema : tenantSchema);
          if (mode === 'viewer') result.buttons = [];
          if (mode === 'package-masked' && path.startsWith(packageBase)) {
            Object.assign(result.schema.properties.code, {readOnly: true, 'x-field-access': 'MASKED'}); delete result.schema['x-query'].fields.code;
          }
          respond(result); return true;
        }
        respond(page(path === tenantBase ? tenants.map(row=>({...row, ...(mode === 'personal' ? {tenantType: 'PERSONAL'} : {})}))
          : parentPackages.map(row=>({...row, ...(mode === 'package-masked' ? {code: '***'} : {})})), path === tenantBase ? tenantSchema : packageSchema, url)); return true;
      }
      const spec = path.startsWith(tenantBase + '/users/') ? {rows: members, schema: memberSchema, grants: memberGrants, parent: 'tenantId', key: 'id', kind: 'members'}
        : path.startsWith(packageBase + '/items') ? {rows: packages, schema: packageSchema, grants: packageGrants, parent: 'tenantId', key: 'code', kind: 'packages'}
        : path.startsWith(applicationBase + '/items') ? {rows: applications, schema: applicationSchema, grants: applicationGrants, parent: 'packageCode', key: 'code', kind: 'applications'} : undefined;
      if (req.method === 'GET' && spec) {
        const target = url.searchParams.get(spec.parent);
        if (!spec.grants.has(target)) throw Error('Child read lost its captured parent');
        reads.push({path, mode: currentMode, kind: spec.kind, params: new URLSearchParams(url.searchParams)});
        if (path.endsWith('/schema')) {
          if (mode === 'child-denied') {expectedFailure(503); respond({message: '配置查询结构读取失败（测试）'}, 503); return true;}
          const result = structuredClone(spec.schema);
          if (mode === 'identity-masked' && spec.kind === 'members') {
            Object.assign(result.schema.properties.id, {readOnly: true, 'x-field-access': 'MASKED'}); delete result.schema['x-query'].fields.id;
          }
          respond(result); return true;
        }
        const selected = url.searchParams.get('selected') === 'true';
        const result = page(spec.rows.filter(row=>spec.grants.get(target).has(row[spec.key]) === selected), spec.schema, url);
        if (mode === 'identity-masked' && spec.kind === 'members') result.content = result.content.map(row=>({...row, id: '***'}));
        respond(result); return true;
      }
      if (path === '/common/users/picker/selected') {
        respond((url.searchParams.get('ids')?.split(',') ?? []).flatMap(id=>members.find(row=>row.id === id) ?? [])); return true;
      }
      if (req.method !== 'POST') return false;
      const mutation = path === tenantBase + '/users/authorize' || path === tenantBase + '/users/unauthorized'
        ? {grants: memberGrants, parent: 'tenantId', values: 'userIds', kind: 'members'}
        : path === tenantBase + '/authorize' || path === tenantBase + '/unauthorized'
          ? {grants: packageGrants, parent: 'tenantId', values: 'packageCodes', kind: 'packages'}
          : path === packageBase + '/authorize' || path === packageBase + '/unauthorized'
            ? {grants: applicationGrants, parent: 'packageCode', values: 'applicationCodes', kind: 'applications'} : undefined;
      if (!mutation) return false;
      if (['viewer', 'personal', 'identity-masked'].includes(currentMode)) throw Error('Denied configuration cannot mutate assignments');
      let raw = ''; for await (const chunk of req) raw += chunk;
      const data = JSON.parse(raw), target = data[mutation.parent], values = data[mutation.values];
      if (!mutation.grants.has(target) || !Array.isArray(values) || !values.length || values.length > 100 || values.some(value=>value === '***' || value.startsWith('__assignment_presentation__'))) throw Error('Configuration mutation lost real bounded identities');
      if (mutation.kind === 'members' && path.endsWith('/unauthorized') && values.includes(tenants.find(row=>row.id === target)?.ownerId)) throw Error('Owner cannot be removed');
      const write = {path, data, kind: mutation.kind, mode: currentMode, completed: false}; writes.push(write);
      const fail = failure; failure = false; await wait(450); write.completed = true;
      if (fail) {expectedFailure(503); respond({message: '配置保存失败（测试）'}, 503); return true;}
      for (const value of values) {if (path.endsWith('/authorize')) mutation.grants.get(target).add(value); else mutation.grants.get(target).delete(value);}
      respond([]); return true;
    }};
}
