import {compileLocalQuery} from '../libs/components/DataTable/localQuery.ts';

export const mcpCapabilitiesRoute = {id: 'mcp-capabilities-query', code: 'ai.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/mcp-capabilities-query', component: 'ai/workbench/McpServers', label: 'MCP 分页测试', scopeTypes: ['PLATFORM']};

/** Isolated domain fixtures: focus on bounded option/history queries and form semantics. */
export function createMcpCapabilitiesFixture() {
  const base = '/ai/workbench/mcp/servers', publications = '/ai/workbench/mcp/publications';
  const servers = Array.from({length: 521}, (_, index) => ({id: `Server-${index}`, name: `Server${index}`,
    enabled: true, status: [518, 519].includes(index) ? 'READY' : 'FAILED'}));
  const fields = {id: {type: 'string'}, name: {type: 'string'}, status: {type: 'string'}, enabled: {type: 'boolean'}};
  const tool = {name: 'typed-tool', title: '类型化工具', inputSchema: {type: 'object', required: ['count'],
    additionalProperties: false, properties: {count: {type: 'integer', minimum: 1, title: '次数'}}}};
  const prompt = {name: 'typed-prompt', arguments: [{name: 'subject', title: '主题', required: true}]};
  const snapshots = Array.from({length: 520}, (_, index) => ({id: `Snapshot-${index}`, protocolVersion: '2025-03-26',
    remoteServerName: 'Remote', remoteServerVersion: String(index), schemaHash: `hash-${index}`,
    discoveredAt: new Date(Date.UTC(2026, 9, 9) - index * 60_000).toISOString(), active: index === 0,
    toolCount: 1, resourceCount: 1, resourceTemplateCount: 0, promptCount: 1}));
  const publication = {id: 'Publication-existing', name: '已有发布', code: 'existing', upstreamServerId: 'Server-520',
    status: 'DRAFT', enabled: false, requiredScopes: 'mcp.invoke', rateLimitPerMinute: 60};
  const schema = {schema: {type: 'object', required: ['name', 'code', 'upstreamServerId'], properties: {
    name: {type: 'string', title: '名称'}, code: {type: 'string', title: '标识'},
    upstreamServerId: {type: 'string', title: '上游服务'}, enabled: {type: 'boolean', title: '启用'},
    requiredScopes: {type: 'string', title: 'Scope'}, rateLimitPerMinute: {type: 'integer', title: '限流'},
    status: {type: 'string', title: '状态', readOnly: true},
  }}, buttons: [
    {key: 'add', title: '新增', sort: 1, argumentMinSize: 0, argumentMaxSize: 0},
    {key: 'edit', title: '编辑', sort: 2, argumentMinSize: 1, argumentMaxSize: 1},
  ]};
  let enabled = false;
  const requests = [], writes = [];
  const page = (rows, parameters) => {
    const number = Number(parameters.get('page') ?? 0), size = Number(parameters.get('size') ?? 20);
    if (size > 100 || size < 1) throw Error('MCP fixture received an unbounded page');
    return {content: rows.slice(number * size, (number + 1) * size),
      page: {number, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}};
  };
  return {requests, writes, enable() {enabled = true;},
    async handle(req, url, respond) {
      if (!enabled || !url.pathname.startsWith('/ai/workbench/mcp/')) return false;
      requests.push({path: url.pathname, method: req.method, params: new URLSearchParams(url.searchParams)});
      if (req.method !== 'GET') {
        let body = ''; for await (const chunk of req) body += chunk;
        const data = JSON.parse(body);
        writes.push({path: url.pathname, data});
        await new Promise(resolve => setTimeout(resolve, 100));
        respond({content: [{type: 'text', text: 'done'}]}); return true;
      }
      if (url.pathname === `${base}/workbench-permissions`) {
        respond({viewCapabilities: true, viewPublications: true, callTool: true, readResource: true, getPrompt: true}); return true;
      }
      if (url.pathname === base) {
        const query = compileLocalQuery(fields, {filters: Object.fromEntries([...url.searchParams]
          .filter(([key]) => !['page', 'size', 'sort'].includes(key))), sort: url.searchParams.getAll('sort')});
        respond(page(servers.filter(query.matches).sort(query.compare), url.searchParams)); return true;
      }
      if (url.pathname === publications) {respond(page([publication], url.searchParams)); return true;}
      if (url.pathname === `${publications}/schema`) {respond(schema); return true;}
      const match = new RegExp(`^${base}/(Server-\\d+)/(.*)$`).exec(url.pathname);
      if (!match) return false;
      if (match[2] === 'tools') respond([{...tool, title: `${tool.title} ${match[1]}`}]);
      else if (match[2] === 'resources') respond([{uri: 'demo://resource', name: 'demo', title: '演示资源'}]);
      else if (match[2] === 'resource-templates') respond([]);
      else if (match[2] === 'prompts') respond([prompt]);
      else if (match[2] === 'snapshots') respond(page(snapshots, url.searchParams));
      else if (match[2].startsWith('snapshots/')) {
        const snapshot = snapshots.find(item => item.id === match[2].slice('snapshots/'.length));
        respond({...snapshot, tools: [tool], resources: [], resourceTemplates: [], prompts: [], capabilities: {tools: {}}});
      } else return false;
      return true;
    }};
}
