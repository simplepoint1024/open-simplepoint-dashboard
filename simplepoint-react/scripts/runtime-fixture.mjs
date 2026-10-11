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
  const requests = [], writes = [], createdWorkloads = new Map();
  let enabled = false, failBase, mutations = false, failWrite, failAfterCommit = false, readonly = false;
  return {datasets, requests, writes, createdWorkloads, enable() {enabled = true;}, failNext(base) {failBase = base;},
    enableWrites() {
      enabled = true; mutations = true;
      Object.assign(datasets.find(item=>item.kind==='Pool').rows[0], {minReplicas: 2, maxReplicas: 5, desiredReplicas: 3, status: 'READY', currentReplicas: 0, readyReplicas: 0});
      Object.assign(datasets.find(item=>item.kind==='Node').rows[0], {seccompEnforced: undefined, appArmorEnforced: '***',
        requireSupplyChainAdmission: undefined, requireImageDigest: undefined, maxWorkloads: undefined, cachedImageDigests: undefined});
    },
    failWrite(path) {failWrite = path;}, failAfterCommit() {failAfterCommit = true;}, setReadonly(value) {readonly = value;},
    async handle(req, url, respond, expectedFailure) {
      if (!enabled) return false;
      if (url.pathname === '/ai/workbench/mcp/servers/workbench-permissions') {
        respond({viewRuntime: true, viewNodes: true, managePools: !readonly, manageWorkloads: !readonly, manageSecrets: !readonly}); return true;
      }
      const dataset = datasets.find(item => url.pathname === item.base || url.pathname.startsWith(item.base + '/'));
      const server = url.pathname === '/ai/workbench/mcp/servers';
      if (!dataset && !server) return false;
      if (req.method !== 'GET') {
        if (!mutations || readonly) throw Error('Runtime query smoke must not mutate runtime resources');
        let raw = ''; for await (const chunk of req) raw += chunk;
        const data = JSON.parse(raw), path = url.pathname;
        writes.push({path, method: req.method, data});
        await new Promise(resolve => setTimeout(resolve, 230));
        if (path === failWrite) {failWrite = undefined; expectedFailure(503); return respond({message: 'Runtime 写入失败（测试）'}, 503), true;}
        const suffix = path.slice(dataset.base.length);
        if (dataset.kind === 'Pool') {
          const [id, action] = suffix.slice(1).split('/'), row = dataset.rows.find(item=>item.id===id);
          if (action === 'scale') {
            if (!Number.isInteger(data.desiredReplicas) || data.desiredReplicas < row.minReplicas || data.desiredReplicas > row.maxReplicas) throw Error('Invalid captured pool bounds');
            row.desiredReplicas = data.desiredReplicas;
          } else if (action === 'disable') row.status = 'DISABLED';
          else if (action === 'redeploy' || action === 'activate') row.status = 'READY';
          else if (req.method === 'DELETE') {dataset.rows.splice(dataset.rows.indexOf(row), 1); return respond(true), true;}
          else throw Error('Unexpected pool mutation');
          return respond(row), true;
        }
        if (dataset.kind === 'Secret') {
          if (suffix.endsWith('/rotate')) return respond(dataset.rows.find(row=>row.id===suffix.split('/')[1])), true;
          if (req.method === 'DELETE') {data.forEach(id=>{const index=dataset.rows.findIndex(row=>row.id===id); if(index>=0) dataset.rows.splice(index,1);}); return respond(true), true;}
          if (suffix !== '' || !data.code || !data.name.trim() || !data.value) throw Error('Invalid secret creation');
          const row = {id: 'secret-created', name: data.name, code: data.code, hasValue: true, enabled: data.enabled};
          dataset.rows.unshift(row); return respond(row), true;
        }
        if (dataset.kind === 'Workload') {
          if (suffix.endsWith('/stop')) {const row=dataset.rows.find(row=>row.id===suffix.split('/')[1]); row.status='CANCELLED'; return respond(row), true;}
          if (suffix !== '' || !data.executionId) throw Error('Workload retries require a real stable execution ID');
          if (!createdWorkloads.has(data.executionId)) {
            const row = {id: 'workload-created', executionId: data.executionId, serverId: data.serverId, status: 'PENDING', imageReference: data.imageReference};
            createdWorkloads.set(data.executionId, row); dataset.rows.unshift(row);
          }
          if (failAfterCommit) {failAfterCommit = false; expectedFailure(503); return respond({message: 'Runtime 提交后响应失败（测试）'}, 503), true;}
          return respond(createdWorkloads.get(data.executionId)), true;
        }
        throw Error('Unexpected Runtime mutation');
      }
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
