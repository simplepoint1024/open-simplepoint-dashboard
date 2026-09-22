import {readFileSync} from 'node:fs';
import {compileLocalQuery} from '../libs/components/DataTable/localQuery.ts';

export const runtimeRoute = {id: 'runtime-query', code: 'ai.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/runtime-query', component: 'ai/workbench/McpServers', label: 'Runtime 查询测试', scopeTypes: ['PLATFORM']};

export function createRuntimeFixture() {
  const specs = [
    {kind: 'Node', plural: 'nodes', field: 'displayName', title: 'ai.runtime.field.node'},
    {kind: 'Pool', plural: 'pools', field: 'name', title: 'ai.runtime.field.name'},
    {kind: 'Secret', plural: 'secrets', field: 'name', title: 'ai.runtime.field.name'},
    {kind: 'Workload', plural: 'workloads', field: 'executionId', title: 'ai.runtime.field.executionId'},
  ];
  const datasets = specs.map(spec => {
    const schema = JSON.parse(readFileSync(new URL(`./runtime-schemas/AiRuntime${spec.kind}.json`, import.meta.url), 'utf8'));
    const fields = Object.fromEntries(Object.entries(schema.schema['x-query'].fields).map(([name, field]) => [name,
      {type: ['boolean', 'Boolean'].includes(field.javaType) ? 'boolean' : ['int', 'long', 'Integer', 'Long'].includes(field.javaType)
        ? 'integer' : field.javaType === 'Instant' ? 'date-time' : 'string'}]));
    const rows = Array.from({length: 520}, (_, index) => {
      const padded = String(index).padStart(3, '0');
      const row = Object.fromEntries(Object.entries(fields).map(([field, capability]) => [field, capability.type === 'boolean' ? true
        : capability.type === 'integer' ? index : capability.type === 'date-time' ? '2026-09-19T00:00:00Z' : `${field}-${padded}`]));
      return {...row, id: `${spec.kind}-${padded}`, [spec.field]: `Item${padded}`, name: `Item${padded}`, code: `code-${padded}`,
        serverId: `Server-${padded}`, status: schema.schema.properties.status?.enum?.[0], enabled: true,
        imageDigest: 'sha256:' + 'a'.repeat(64), egressAllowlist: [], secretIds: [], cachedImageDigests: [], labels: {},
        hasValue: true, networkMode: 'none'};
    });
    return {...spec, base: `/ai/workbench/runtime/${spec.plural}`, schema, fields, rows};
  });
  const servers = Array.from({length: 520}, (_, index) => ({id: `Server-${String(index).padStart(3, '0')}`,
    name: `Server${String(index).padStart(3, '0')}`, code: `server-${index}`, deploymentType: 'MANAGED_OCI', enabled: true}));
  const requests = [];
  let enabled = false, failBase;
  return {datasets, requests, enable() {enabled = true;}, failNext(base) {failBase = base;},
    async handle(req, url, respond, expectedFailure) {
      if (!enabled) return false;
      if (url.pathname === '/ai/workbench/mcp/servers/workbench-permissions') {
        respond({viewRuntime: true, viewNodes: true, managePools: true, manageWorkloads: true, manageSecrets: true}); return true;
      }
      const dataset = datasets.find(item => url.pathname === item.base || url.pathname === `${item.base}/schema`);
      const server = url.pathname === '/ai/workbench/mcp/servers';
      if (!dataset && !server) return false;
      if (req.method !== 'GET') throw Error('Runtime query smoke must not mutate runtime resources');
      if (url.pathname.endsWith('/schema')) return respond(dataset.schema), true;
      requests.push({kind: dataset?.kind ?? 'Server', params: new URLSearchParams(url.searchParams)});
      if (failBase === url.pathname) {failBase = undefined; expectedFailure(400); respond({code: 'INVALID_QUERY', message: '测试条件失效'}, 400); return true;}
      const page = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
      if (size > 100 || size < 1) throw Error('Runtime query requested an unbounded page');
      const fields = dataset?.fields ?? {id: {type: 'string'}, name: {type: 'string'}, deploymentType: {type: 'string'}, enabled: {type: 'boolean'}};
      const query = compileLocalQuery(fields, {filters: Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort'].includes(key))), sort: url.searchParams.getAll('sort')});
      const rows = (dataset?.rows ?? servers).filter(query.matches).sort(query.compare);
      respond({content: rows.slice(page * size, (page + 1) * size), page: {number: page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}});
      return true;
    }};
}
