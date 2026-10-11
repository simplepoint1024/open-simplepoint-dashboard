import {compileLocalQuery, localQuerySchema} from '../libs/components/DataTable/localQuery.ts';

export const jdbcArtifactRoutes = [
  {id: 'driver-artifact', code: 'dna.drivers.view', type: 'PAGE', routeKind: 'item', path: '/smoke/driver-artifact',
    component: 'dna/platform/JdbcDriver', label: '驱动导入测试', scopeTypes: ['PLATFORM']},
  {id: 'dialect-artifact', code: 'dna.dialects.view', type: 'PAGE', routeKind: 'item', path: '/smoke/dialect-artifact',
    component: 'dna/platform/JdbcDialect', label: '方言导入测试', scopeTypes: ['PLATFORM']},
];
const driversBase = '/dna/platform/dna/drivers', dialectsBase = '/dna/platform/dna/dialects';
const fields = {id: {type: 'string'}, name: {type: 'string'}, code: {type: 'string'}, databaseType: {type: 'string'},
  downloadUrl: {type: 'string'}, enabled: {type: 'boolean'}, description: {type: 'string'}};
const schema = localQuerySchema(fields);
Object.values(schema.properties).forEach(property => {property['x-ui'] = {'x-list-visible': true};});
schema.required = ['name', 'code', 'databaseType'];
schema.properties.databaseType.enum = ['POSTGRESQL', 'MYSQL'];
const artifact = {localJarPath: '/isolated-fixture/actual-driver.jar', driverClassName: 'fixture.Driver',
  jdbcUrlPattern: '^jdbc:fixture:.*$', version: '1.0', downloadedAt: '2026-10-10T00:00:00Z'};

/** Real multipart transport, isolated artifact results; never loads or executes a JAR. */
export function createJdbcArtifactFixture() {
  const drivers = Array.from({length: 521}, (_, index) => ({id: 'driver-' + index, name: 'Driver' + index,
    code: 'driver-' + index, databaseType: 'POSTGRESQL', downloadUrl: 'https://fixture.test/driver.jar', enabled: true}));
  const sources = Array.from({length: 13}, (_, index) => ({id: 'dialect-source-' + index, name: 'DialectSource' + index,
    sourceType: 'UPLOAD', enabled: true, discoveredDialectCodes: ['postgresql'], ...artifact}));
  const dialects = [{code: 'postgresql', name: 'PostgreSQL', sourceType: 'CLASSPATH', className: 'fixture.Dialect', boundDriverCodes: ['driver-0']}];
  const writes = [], requests = [];
  let active = false, mode = 'all', failure;
  return {writes, requests, sources, enable() {active = true;}, mode(value) {mode = value;}, failWrite(path) {failure = path;},
    async handle(req, url, respond, expectedFailure) {
      const path = url.pathname;
      if (!active || ![driversBase, dialectsBase].some(base => path === base || path.startsWith(base + '/'))) return false;
      requests.push({path, method: req.method, params: new URLSearchParams(url.searchParams)});
      if (req.method !== 'GET') {
        if (['readonly', 'denied'].includes(mode)) throw Error('Read-only artifact page issued a mutation');
        if ((path.endsWith('/url') || req.method === 'DELETE') && mode !== 'all') throw Error('Source creation/deletion requires its independent authority');
        const chunks = []; for await (const chunk of req) chunks.push(chunk);
        const body = Buffer.concat(chunks), contentType = req.headers['content-type'] ?? '';
        const data = contentType.startsWith('multipart/form-data') ? undefined : body.length ? JSON.parse(body.toString()) : undefined;
        if (path.endsWith('/upload')) {
          if (!/boundary=/.test(contentType) || !body.includes(Buffer.from('filename="actual.jar"')) || !body.includes(Buffer.alloc(2048, 0))) {
            throw Error('Artifact upload must use the selected binary file and browser multipart boundary');
          }
          if (mode === 'download') throw Error('Download authority does not imply upload authority');
        } else if (path.endsWith('/download') && mode === 'upload') throw Error('Upload authority does not imply download authority');
        const write = {path, method: req.method, data, body, params: new URLSearchParams(url.searchParams), completed: false};
        writes.push(write);
        await new Promise(resolve => setTimeout(resolve, 400));
        write.completed = true;
        if (failure === path) {failure = undefined; expectedFailure(503); return respond({message: 'JDBC 导入失败（测试）'}, 503), true;}
        if (path === dialectsBase + '/sources' && req.method === 'DELETE') {
          const ids = url.searchParams.get('ids').split(',');
          ids.forEach(id => {const index = sources.findIndex(source => source.id === id); if (index >= 0) sources.splice(index, 1);});
          return respond(true), true;
        }
        return respond({...artifact, code: data?.code ?? 'actual-driver', driverCode: 'actual-driver'}), true;
      }
      if (mode === 'denied' && [driversBase + '/schema', dialectsBase + '/workbench-permissions'].includes(path)) {
        expectedFailure(503); return respond({message: 'JDBC 权限读取失败（测试）'}, 503), true;
      }
      if (path === driversBase + '/schema') {
        const current = structuredClone(schema);
        if (mode === 'masked') current.properties.name.readOnly = true;
        const buttons = [
          ...(!['readonly', 'download'].includes(mode) ? [{key: 'upload', title: '上传驱动', sort: 1, argumentMinSize: 0, argumentMaxSize: 1}] : []),
          ...(!['readonly', 'upload'].includes(mode) ? [{key: 'download', title: '下载驱动', sort: 2, argumentMinSize: 1, argumentMaxSize: 1}] : []),
        ];
        return respond({schema: current, buttons}), true;
      }
      if (path === driversBase) {
        const page = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
        if (size > 100 || size < 1) throw Error('Driver list must use real bounded pages');
        const query = compileLocalQuery(fields, {filters: Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort'].includes(key))), sort: url.searchParams.getAll('sort')});
        const rows = drivers.filter(query.matches).sort(query.compare);
        return respond({content: rows.slice(page * size, (page + 1) * size), page: {number: page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}}), true;
      }
      if (path === dialectsBase + '/workbench-permissions') return respond({canCreateUrl: mode === 'all', canUpload: ['all', 'upload'].includes(mode), canDelete: mode === 'all'}), true;
      if (path === dialectsBase + '/sources') return respond(sources), true;
      if (path === dialectsBase) return respond(dialects), true;
      throw Error('Unexpected artifact fixture read ' + path);
    }};
}
