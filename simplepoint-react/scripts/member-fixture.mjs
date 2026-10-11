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
  const removedOrganizationUsers = new Set();
  const organizationRows = Array.from({length: 125}, (_, i) => ({userId: `org-user-${i}`,
    name: `OrgUser${String(i).padStart(3, '0')}`, email: `org${i}@example.test`, orgId: i % 2 ? `child-${i}` : 'root-0',
    orgName: i % 2 ? 'Child' : 'Root', enabled: true, userType: 'INTERNAL', revision: 0}));
  rows.forEach(row => {row.userType = 'EXTERNAL';});
  const organizationWrites = [];
  const writes = [];
  let memberWriteFailure = 0, memberWriteDelay = 250, manage = true, configureRoles = true, capabilitiesFailure = false, maskedIdentity = false;
  let failOrganizationWrite = false, readOnlyType = false, readOnlyOrg = false, hideEditableFields = false;
  let fail = false;
  let reorder = false;
  let reorderedSchemas = 0;
  return {
    organizationWrites, organizationRows, rows, writes,
    failWrite(status = 503) {memberWriteFailure = status;}, writeDelay(value) {memberWriteDelay = value;},
    manage(value) {manage = value;}, maskIdentity(value) {maskedIdentity = value;},
    configureRoles(value) {configureRoles = value;},
    failCapabilities() {capabilitiesFailure = true;},
    failOrganizationWrite() {failOrganizationWrite = true;}, readOnlyType(value) {readOnlyType = value;},
    readOnlyOrg(value) {readOnlyOrg = value;},
    hideEditableFields(value) {hideEditableFields = value;},
    failNext() {fail = true;},
    reorderSchema() {reorder = true;},
    get reorderedSchemas() {return reorderedSchemas;},
    async handle(req, url, respond, expectedFailure) {
      const base = '/common/tenant-members';
      if (url.pathname === '/common/platform/organizations/options') {
        const options = Array.from({length: 145}, (_, index) => ({id: `org-${index}`, name: `Organization ${index}`,
          enabled: true, hasChildren: false}));
        const ids = url.searchParams.get('ids')?.split(','), keyword = url.searchParams.get('keyword') ?? '';
        const matches = options.filter(row => (!ids || ids.includes(row.id)) && row.name.includes(keyword));
        const page = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 30);
        if (size > 100) throw Error('Organization picker must use bounded options pages');
        return respond({content: matches.slice(page * size, (page + 1) * size), number: page, size,
          hasNext: (page + 1) * size < matches.length}), true;
      }
      if (url.pathname === '/common/platform/dictionaries/options'
          && url.searchParams.get('dictionaryCode') === 'tenant.member.user-type') {
        return respond(['INTERNAL', 'EXTERNAL', 'REFERENCE'].map(value => ({value,
          label: `i18n:tenant-members.userType.${value}`}))), true;
      }
      if ((url.pathname === base && req.method === 'POST')
        || (url.pathname.startsWith(`${base}/member-`) && ['PUT', 'DELETE'].includes(req.method))) {
        let raw = ''; for await (const chunk of req) raw += chunk;
        const body = raw ? JSON.parse(raw) : null, id = decodeURIComponent(url.pathname.split('/').at(-1));
        const write = {method: req.method, id, body, completed: false}; writes.push(write);
        const failure = memberWriteFailure; memberWriteFailure = 0;
        if (body && !Array.isArray(body) && Object.keys(body).some(key => !['email', 'orgId', 'enabled', 'revision', 'userType'].includes(key)))
          throw Error('Member write leaked non-command fields');
        if (!manage) throw Error('Member write reached a read-only owner');
        const target = rows.find(row => row.userId === id);
        if (req.method === 'PUT' && (!target || body.revision !== target.revision || !('orgId' in body)
          || readOnlyType && 'userType' in body || readOnlyOrg && body.orgId !== target.orgId))
          throw Error('Member write lost its original revision/organization or writable field boundary');
        await new Promise(resolve => setTimeout(resolve, memberWriteDelay)); write.completed = true;
        if (failure) {expectedFailure(failure); return respond({message: failure === 409 ? '成员版本冲突（测试）' : '成员事务保存失败（测试）'}, failure), true;}
        if (req.method === 'POST') {
          rows.push({userId: 'member-added', name: 'Added Member', email: body.email, orgId: body.orgId ?? null,
            enabled: true, userType: body.userType ?? 'INTERNAL', revision: 0});
        } else if (req.method === 'PUT') Object.assign(target, body, {revision: target.revision + 1});
        else if (target) rows.splice(rows.indexOf(target), 1);
        return respond(null), true;
      }
      if (url.pathname.startsWith(`${base}/by-organization/`) && req.method === 'DELETE') {
        let body = ''; for await (const chunk of req) body += chunk;
        const command = JSON.parse(body);
        const user = decodeURIComponent(url.pathname.split('/').at(-1));
        if (command.orgId !== 'root-0' || command.revision !== 0 || !user.startsWith('org-user-'))
          throw Error('Removal must submit the selected organization and member revision');
        removedOrganizationUsers.add(user);
        return respond(null), true;
      }
      if (url.pathname.startsWith(`${base}/by-organization/`) && req.method === 'PUT') {
        let raw = ''; for await (const chunk of req) raw += chunk;
        const body = JSON.parse(raw), id = decodeURIComponent(url.pathname.split('/').at(-1));
        if (Object.keys(body).some(key => !['orgId', 'revision', 'userType'].includes(key)) || readOnlyType && 'userType' in body)
          throw Error('Organization write leaked protected member fields');
        const target = organizationRows.find(row => row.userId === id) ?? rows.find(row => row.userId === id);
        if (!target || target.revision !== body.revision || !body.orgId) throw Error('Organization write lost its captured identity/revision');
        organizationWrites.push({id, body});
        const fail = failOrganizationWrite; failOrganizationWrite = false;
        await new Promise(resolve => setTimeout(resolve, 250));
        if (fail) {expectedFailure(503); return respond({message: '组织成员事务保存失败'}, 503), true;}
        Object.assign(target, body, {revision: target.revision + 1});
        if (!organizationRows.some(row => row.userId === id)) organizationRows.push({...target, orgName: 'Root'});
        return respond(null), true;
      }
      if (url.pathname === `${base}/by-organization` || url.pathname === `${base}/organization-candidates`) {
        const candidate = url.pathname.endsWith('organization-candidates');
        const source = candidate ? rows : organizationRows.filter(row => !removedOrganizationUsers.has(row.userId));
        const keyword = (url.searchParams.get('keyword') ?? '').toLowerCase();
        const matches = source.filter(row => `${row.name} ${row.email} ${row.userId}`.toLowerCase().includes(keyword));
        const page = Number(url.searchParams.get('page') ?? 0);
        const size = Number(url.searchParams.get('size') ?? 20);
        if (size > 100) throw Error('Organization member fixture refuses unbounded pages');
        return respond({content: matches.slice(page * size, (page + 1) * size),
          page: {number: page, size, totalElements: matches.length,
            totalPages: Math.ceil(matches.length / size)}}), true;
      }
      if (url.pathname === `${base}/schema` || url.pathname === `${base}/by-organization/schema`) {
        const current = structuredClone(schema);
        if (maskedIdentity) {
          current.schema.properties.userId['x-field-access'] = 'MASKED';
          current.schema.properties.revision['x-field-access'] = 'MASKED';
        }
        if (readOnlyType) current.schema.properties.userType.readOnly = true;
        if (readOnlyOrg) current.schema.properties.orgId.readOnly = true;
        if (hideEditableFields) for (const field of ['enabled', 'userType']) {
          delete current.schema.properties[field]; delete current.schema['x-query'].fields[field];
        }
        if (reorder) {
          current.schema['x-query'] = Object.fromEntries(Object.entries(current.schema['x-query']).reverse());
          reorderedSchemas++;
        }
        return respond(current), true;
      }
      if (url.pathname === `${base}/capabilities` || url.pathname === `${base}/by-organization/capabilities`) {
        if (capabilitiesFailure) {capabilitiesFailure = false; expectedFailure(503); return respond({message: '成员管理能力读取失败（测试）'}, 503), true;}
        return respond({manage, remove: manage, configureRoles: manage && configureRoles}), true;
      }
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
      return respond({content: matches.slice(page * size, (page + 1) * size).map(row => {
        const projected = {...row}; if (maskedIdentity) {projected.userId = '***'; projected.revision = '***';}
        if (hideEditableFields) {delete projected.enabled; delete projected.userType;}
        return projected;
      }),
        page: {number: page, size, totalElements: matches.length, totalPages: Math.ceil(matches.length / size)}}), true;
    },
  };
}
