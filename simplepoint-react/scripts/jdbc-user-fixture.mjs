import {compileLocalQuery, localQuerySchema} from '../libs/components/DataTable/localQuery.ts';

export const jdbcUserRoute = {id: 'jdbc-user-query', code: 'dna.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/jdbc-users', component: 'dna/platform/FederationJdbcUser', label: 'JDBC 分配测试', scopeTypes: ['PLATFORM']};

/** Isolated domain semantics; no production users, credentials or JDBC mutations. */
export function createJdbcUserFixture() {
  const base = '/dna/platform/dna/federation/jdbc-users';
  const sources = Array.from({length: 521}, (_, index) => ({id: `Source-${index}`, name: `Source${index}`,
    code: `source-${String(index).padStart(3, '0')}`, databaseProductName: 'FixtureDB'}));
  const users = Array.from({length: 630}, (_, index) => ({id: `User-${index}`, name: `成员${index}`,
    email: `member${index}@fixture.test`, phoneNumber: `130000${String(index).padStart(5, '0')}`}));
  const grants = new Map(sources.slice(0, 260).map(source => [source.id, {id: `Grant-${source.id}`,
    catalogId: source.id, catalogName: source.name, catalogCode: source.code, userId: 'User-0',
    userDisplayName: users[0].name, userEmail: users[0].email, operationPermissions: ['METADATA', 'QUERY'], enabled: true}]));
  const candidateFields = {name: {type: 'string'}, code: {type: 'string'}, databaseProductName: {type: 'string'}};
  const grantFields = {catalogName: {type: 'string'}, catalogCode: {type: 'string'}, userDisplayName: {type: 'string'},
    userEmail: {type: 'string'}, userId: {type: 'string'}, enabled: {type: 'boolean'}};
  const grantSchema = localQuerySchema(grantFields);
  grantSchema.properties.operationPermissions = {type: 'array', items: {type: 'string'}, title: '操作权限',
    'x-ui': {'x-list-visible': true}};
  Object.values(grantSchema.properties).forEach(property => {property['x-ui'] = {'x-list-visible': true};});
  const actions = [{key: 'add', title: '新增', sort: 0, argumentMinSize: 0, argumentMaxSize: 0},
    {key: 'edit', title: '编辑', sort: 1, argumentMinSize: 1, argumentMaxSize: 1}];
  let enabled = false, failNext = false;
  const requests = [], writes = [];
  const page = (rows, parameters, fields, controls = []) => {
    const number = Number(parameters.get('page') ?? 0), size = Number(parameters.get('size') ?? 20);
    if (!Number.isSafeInteger(size) || size > 100 || size < 1) throw Error('Unbounded JDBC assignment query');
    const filters = Object.fromEntries([...parameters].filter(([key]) =>
      !['page', 'size', 'sort', ...controls].includes(key)));
    const query = compileLocalQuery(fields, {filters, sort: parameters.getAll('sort')});
    const result = rows.filter(query.matches).sort(query.compare);
    return {content: result.slice(number * size, (number + 1) * size),
      page: {number, size, totalElements: result.length, totalPages: Math.ceil(result.length / size)}};
  };
  return {requests, writes, grants, enable() {enabled = true;}, failWrite() {failNext = true;},
    async handle(req, url, respond, expectedFailure) {
      if (!enabled || !url.pathname.startsWith(base)) return false;
      const suffix = url.pathname.slice(base.length);
      requests.push({path: url.pathname, method: req.method, params: new URLSearchParams(url.searchParams)});
      if (req.method !== 'GET') {
        let body = ''; for await (const chunk of req) body += chunk;
        const data = JSON.parse(body);
        writes.push({path: url.pathname, data, grantId: url.searchParams.get('grantId')});
        await new Promise(resolve => setTimeout(resolve, 300));
        if (failNext) {failNext = false; expectedFailure(503); respond({message: 'JDBC 写入失败（测试）'}, 503); return true;}
        if (suffix === '/authorize') {
          data.dataSourceIds.forEach(id => {
            const source = sources.find(row => row.id === id);
            grants.set(id, {id: `Grant-${id}`, catalogId: id, catalogName: source.name, catalogCode: source.code,
              userId: data.userId, userDisplayName: users[0].name, operationPermissions: ['METADATA', 'QUERY'], enabled: true});
          });
          respond(data.dataSourceIds.map(id => grants.get(id)));
        } else if (suffix === '/unauthorized') {
          data.dataSourceIds.forEach(id => grants.delete(id)); respond(true);
        } else if (suffix === '/permissions') {
          const grant = [...grants.values()].find(row => row.id === url.searchParams.get('grantId'));
          grant.operationPermissions = data; respond(grant);
        } else throw Error(`Unexpected JDBC write ${suffix}`);
        return true;
      }
      if (suffix === '/items/schema') respond({schema: localQuerySchema(candidateFields), buttons: []});
      else if (['/schema', '/assigned/schema'].includes(suffix)) respond({schema: grantSchema, buttons: actions});
      else if (suffix === '') respond(page([...grants.values()], url.searchParams, grantFields));
      else if (suffix === '/items') {
        const userId = url.searchParams.get('userId');
        respond(page(sources.filter(source => grants.get(source.id)?.userId !== userId),
          url.searchParams, candidateFields, ['userId', 'selected']));
      } else if (suffix === '/assigned') {
        respond(page([...grants.values()].filter(grant => grant.userId === url.searchParams.get('userId')),
          url.searchParams, grantFields, ['userId', 'selected']));
      } else if (suffix === '/user-options') {
        const keyword = url.searchParams.get('keyword') ?? '';
        const selected = keyword.length < 3 ? [] : users.filter(user =>
          user.email.startsWith(keyword) || user.phoneNumber.startsWith(keyword));
        respond(page(selected, url.searchParams, {}, ['keyword']));
      } else throw Error(`Unexpected or full-catalog JDBC read ${suffix}`);
      return true;
    }};
}
