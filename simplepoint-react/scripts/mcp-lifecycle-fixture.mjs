import {compileLocalQuery, localQuerySchema} from '../libs/components/DataTable/localQuery.ts';
export const mcpLifecycleRoute = {id: 'mcp-deployment-standard', code: 'ai.mcp.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/mcp-deployment-standard', component: 'ai/workbench/McpServers', label: '托管部署标准测试', scopeTypes: ['PLATFORM', 'TENANT']};
export function createMcpLifecycleFixture() {
  const base = '/ai/workbench/mcp/servers', pools = '/ai/workbench/runtime/pools', workloads = '/ai/workbench/runtime/workloads';
  let active = false, failPermissions = false, failWrite = false, delay = 180, inReads = 0, maxReads = 0;
  const reads = [], writes = [], serverFields = {name: {type: 'string'}, code: {type: 'string'}, deploymentType: {type: 'string'}, status: {type: 'string'}};
  const workloadFields = {poolId: {type: 'string'}, status: {type: 'string'}, createdAt: {type: 'date-time'}, id: {type: 'string'}};
  const server = {id: 'managed-server', name: 'Actual managed server', code: 'actual-managed', deploymentType: 'MANAGED_OCI',
    authenticationType: 'NONE', enabled: true, status: 'READY', activeSnapshotId: 'snapshot-actual'};
  const oauth = {...server, id: 'oauth-server', name: 'Actual OAuth server', deploymentType: 'REMOTE', authenticationType: 'OAUTH2'};
  const pool = {id: 'actual-pool', name: 'Actual Pool', code: 'actual-pool', serverId: server.id, status: 'DISABLED',
    currentReplicas: 0, readyReplicas: 0, desiredReplicas: 0, prewarmedNodes: 0, imageReference: 'registry.test/actual:1',
    imageDigest: 'sha256:' + 'a'.repeat(64), requestedNanoCpus: 500000000, requestedMemoryBytes: 268435456, requestedPidsLimit: 128};
  const rows = Array.from({length: 521}, (_, index) => ({id: 'workload-' + index, poolId: pool.id, serverId: server.id,
    runtimeWorkloadId: 'actual-workload-' + index, status: index === 520 ? 'PENDING' : 'SUCCEEDED',
    createdAt: new Date(Date.UTC(2026, 9, 9) - index * 60000).toISOString()}));
  const schema = {schema: {type: 'object', ...localQuerySchema(serverFields)}, buttons: [
    {key: 'discover', title: '发现能力', sort: 1, argumentMinSize: 1, argumentMaxSize: 1},
    {key: 'deploy', title: '部署', sort: 2, argumentMinSize: 1, argumentMaxSize: 1},
    {key: 'authorize', title: 'OAuth 授权', sort: 3, argumentMinSize: 1, argumentMaxSize: 1},
  ]};
  Object.values(schema.schema.properties).forEach(field => {field['x-ui'] = {'x-list-visible': true};});
  const page = (data, params) => {
    const number = Number(params.get('page') ?? 0), size = Number(params.get('size') ?? 20);
    if (size < 1 || size > 100) throw Error('Unbounded MCP lifecycle query');
    return {content: data.slice(number * size, (number + 1) * size), page: {number, size, totalElements: data.length, totalPages: Math.ceil(data.length / size)}};
  };
  return {reads, writes, pool, enable() {active = true;}, failPermissions() {failPermissions = true;}, failWrite() {failWrite = true;},
    delay(value) {delay = value;}, maxConcurrentReads() {return maxReads;}, endActive() {rows[520].status = 'SUCCEEDED';},
    async handle(req, url, respond, expectedFailure) {
      if (!active || ![base, pools, workloads].some(prefix => url.pathname.startsWith(prefix))) return false;
      const path = url.pathname, params = new URLSearchParams(url.searchParams), tenant = req.headers['x-tenant-id'];
      if (req.method === 'GET') {
        reads.push({path, params, tenant});
        if (path === base + '/workbench-permissions') {
          if (failPermissions) {failPermissions = false; expectedFailure(503); return respond({message: 'MCP 权限失败（测试）'}, 503), true;}
          return respond({viewServers: true, discoverServer: tenant === '__platform__', managePools: tenant === '__platform__'}), true;
        }
        if (path === base + '/schema') return respond(schema), true;
        if (path === base) return respond(page([server, oauth], params)), true;
        if (path === pools) return respond(page([pool], params)), true;
        inReads++; maxReads = Math.max(maxReads, inReads);
        try {
          await new Promise(resolve => setTimeout(resolve, delay));
          if (path === base + '/' + server.id) return respond(server), true;
          if (path === pools + '/' + pool.id) return respond(pool), true;
          if (path === workloads) {
            const filters = Object.fromEntries([...params].filter(([key]) => !['page', 'size', 'sort'].includes(key)));
            const query = compileLocalQuery(workloadFields, {filters, sort: params.getAll('sort')});
            return respond(page(rows.filter(query.matches).sort(query.compare), params)), true;
          }
          throw Error('Unbounded or unexpected deployment status read: ' + path);
        } finally {inReads--;}
      }
      if (req.method !== 'POST') throw Error('Unexpected MCP lifecycle mutation');
      let raw = ''; for await (const chunk of req) raw += chunk;
      writes.push({path, body: JSON.parse(raw), tenant});
      await new Promise(resolve => setTimeout(resolve, 280));
      if (failWrite) {failWrite = false; expectedFailure(503); return respond({message: 'MCP 写入失败（测试）'}, 503), true;}
      if (path.endsWith('/oauth/authorize')) return respond({authorizationUrl: 'javascript:unsafe()'}), true;
      if (path.endsWith('/discover')) return respond({serverName: 'Actual discovery', protocolVersion: '2025-03-26', tools: [{name: 'actual-tool', title: 'Actual tool'}]}), true;
      if (path === pools + '/' + pool.id + '/redeploy') return respond(pool), true;
      if (path === pools + '/' + pool.id + '/disable') return respond(pool), true;
      throw Error('Unexpected MCP lifecycle mutation: ' + path);
    }};
}
