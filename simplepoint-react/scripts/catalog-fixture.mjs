export const catalogRoute = {id: 'catalog-standard-test', code: 'ai.catalog.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/catalog-standard', component: 'ai/workbench/Catalog', label: '扩展市场标准测试', scopeTypes: ['PLATFORM', 'TENANT']};
const base = '/ai/workbench/catalog';
export function createCatalogFixture() {
  const internal = Array.from({length: 521}, (_, index) => ({id: 'internal:mcp:' + index, source: 'INTERNAL', kind: 'MCP_SERVER',
    title: 'Internal' + String(index).padStart(3, '0'), code: 'internal-' + index, status: 'READY', importable: false, remoteImportable: false, runtimeImportable: false}));
  const official = Array.from({length: 521}, (_, index) => ({id: 'official-' + index, source: 'OFFICIAL_MCP', kind: 'MCP_SERVER',
    title: 'Official' + String(index).padStart(3, '0'), description: '实际目录元数据', status: 'ACTIVE', version: '1.0.0', importable: true,
    remoteImportable: index % 2 === 0, runtimeImportable: index % 2 === 1, repositoryUrl: index === 520 ? 'javascript:unsafe()' : 'https://example.com/' + index}));
  const reads = [], writes = [];
  let active = false, readOnly = false, failPermissions = false, failRead = false, failWrite = false;
  return {reads, writes, enable() {active = true;}, disable() {active = false;}, readonly(value) {readOnly = value;},
    failPermissions() {failPermissions = true;}, failRead() {failRead = true;}, failWrite() {failWrite = true;},
    async handle(req, url, respond, expectedFailure) {
      if (!active || !url.pathname.startsWith(base)) return false;
      const path = url.pathname, params = new URLSearchParams(url.searchParams), tenant = req.headers['x-tenant-id'];
      if (req.method === 'GET') {
        reads.push({path, params, tenant});
        if (path.endsWith('/workbench-permissions')) {
          if (failPermissions) {failPermissions = false; expectedFailure(503); return respond({errorCode: 'AI_CATALOG_OPERATION_FAILED'}, 503), true;}
          return respond({sync: !readOnly && tenant === '__platform__', importRemote: !readOnly, importRuntime: !readOnly}), true;
        }
        if (path.endsWith('/sync-status')) return respond({status: 'SUCCEEDED', syncMode: 'FULL', lastCompletedAt: '2026-10-10T01:00:00Z'}), true;
        if (path !== base) throw Error('Unexpected catalog read: ' + path);
        if (failRead) {failRead = false; expectedFailure(503); return respond({errorCode: 'AI_CATALOG_OPERATION_FAILED'}, 503), true;}
        const page = Number(params.get('page')), size = Number(params.get('size'));
        if (size > 100 || size < 1 || [...params.keys()].some(key => !['page', 'size', 'query', 'source', 'kind'].includes(key))) throw Error('Invalid catalog page contract');
        const query = (params.get('query') ?? '').toLowerCase(), source = params.get('source');
        const rows = [...(source === 'OFFICIAL_MCP' ? [] : internal), ...(source === 'INTERNAL' ? [] : official)]
          .filter(row => [row.title, row.code, row.description].some(value => value?.toLowerCase().includes(query)));
        return respond({content: rows.slice(page * size, (page + 1) * size), number: page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}), true;
      }
      if (readOnly || req.method !== 'POST') throw Error('Unauthorized catalog mutation');
      let raw = ''; for await (const chunk of req) raw += chunk;
      const body = JSON.parse(raw); writes.push({path, body, tenant});
      if (JSON.stringify(body) !== '{}') throw Error('Catalog import must use the confirmed server defaults');
      await new Promise(resolve => setTimeout(resolve, 250));
      if (failWrite) {failWrite = false; expectedFailure(503); return respond({errorCode: 'AI_CATALOG_OPERATION_FAILED'}, 503), true;}
      if (path.endsWith('/sync')) {
        if (tenant !== '__platform__') throw Error('Tenant may not synchronize the platform Registry');
        return respond({status: 'SUCCEEDED'}), true;
      }
      const match = path.match(/\/catalog\/(official-\d+)\/(import|import-runtime)$/);
      if (!match) throw Error('Unexpected catalog mutation');
      const item = official.find(row => row.id === match[1]);
      if (match[2] === 'import' ? !item?.remoteImportable : !item?.runtimeImportable) throw Error('Catalog import formats must remain distinct');
      const imported = {id: 'created-' + item.id, name: item.title, code: item.id};
      return respond(match[2] === 'import' ? imported : {profile: imported, descriptor: {id: 'descriptor-' + item.id}}), true;
    }};
}
