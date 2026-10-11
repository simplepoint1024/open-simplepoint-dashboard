import {readFileSync} from 'node:fs';
import {compileLocalQuery} from '../libs/components/DataTable/localQuery.ts';

const schemas = Object.fromEntries(['providers', 'models'].map(name => [name,
  JSON.parse(readFileSync(new URL(`./ai-configuration-schemas/${name}.json`, import.meta.url), 'utf8'))]));
const providerBase = '/ai/workbench/providers', modelBase = '/ai/workbench/models';
export const aiProviderRoute = {id: 'ai-configuration-providers', code: 'ai.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/ai-providers', component: 'ai/workbench/Providers', label: 'AI 接入配置测试', scopeTypes: ['PLATFORM']};

/** Real HTTP-exported schemas and production UI; network and writes are isolated from business services. */
export function createAiConfigurationFixture() {
  const providers = Array.from({length: 521}, (_, index) => ({id: `ai-provider-${String(index).padStart(3, '0')}`,
    name: `Provider ${String(index).padStart(3, '0')}`, code: `provider-${index}`, scopeType: 'SYSTEM', tenantId: null,
    vendor: 'OPENAI', providerType: 'OPENAI', baseUrl: 'https://provider.example.test/v1', modelDiscoveryUrl: null,
    hasApiKey: true, enabled: true, autoSyncEnabled: false, allowPrivateNetwork: false, lastStatus: 'SYNCED',
    lastMessage: 'MODEL_SYNC_SUCCEEDED', description: 'Original provider metadata'}));
  providers[520].id = 'literal/provider 520?'; providers[1].enabled = false;
  const models = Array.from({length: 521}, (_, index) => ({id: `ai-model-${String(index).padStart(3, '0')}`,
    providerId: providers[520].id, providerName: providers[520].name, modelId: `model-${String(index).padStart(3, '0')}`,
    displayName: `Model ${String(index).padStart(3, '0')}`, modelType: 'LLM', enabled: true, available: true,
    billingEnabled: true, billingCurrency: 'USD', inputTokenPrice: '0.12345678', outputTokenPrice: '0.23456789',
    discovered: true, pricingAutoDetected: true, typeAutoDetected: true, scopeType: 'SYSTEM', description: 'Original model metadata'}));
  models[0].id = 'literal/model 0?'; models[1].available = false;
  const remote = Array.from({length: 521}, (_, index) => ({modelId: `remote-${String(index).padStart(3, '0')}`, modelType: 'LLM',
    displayName: `Remote ${index}`, ownedBy: 'actual-owner', pricing: {currency: 'USD', inputTokenPrice: String(index / 1000), outputTokenPrice: String(index / 100)}}));
  const reads = [], operations = [], writes = [], generations = [];
  let enabled = false, mode = 'all', nextResult = 'success', nextHold = false;
  return {providers, models, remote, reads, operations, writes, generations, enable() {enabled = true;},
    mode(value) {mode = value;}, result(value) {nextResult = value;}, hold() {nextHold = true;}, release(request = operations.at(-1)) {request?.release?.();},
    async handle(req, url, respond, expectedFailure, respondStream) {
      if (!enabled) return false;
      const path = url.pathname, capturedMode = mode;
      const kind = path.startsWith(providerBase) ? 'providers' : path.startsWith(modelBase) ? 'models' : undefined;
      if (!kind) return false;
      const base = kind === 'providers' ? providerBase : modelBase, rows = kind === 'providers' ? providers : models;
      const actual = structuredClone(schemas[kind]);
      if (capturedMode === 'test-only') actual.buttons = actual.buttons.filter(button => button.key === 'test');
      if (capturedMode === 'no-external') actual.buttons = actual.buttons.filter(button => !['test', 'sync', 'discover', 'debug'].includes(button.key));
      if (capturedMode === 'readonly') for (const [field, property] of Object.entries(actual.schema.properties)) {
        if (['description', 'id'].includes(field)) continue;
        const masked = ['enabled', 'vendor', 'modelType', 'inputTokenPrice', 'billingEnabled'].includes(field);
        property.readOnly = true; property['x-field-access'] = masked ? 'MASKED' : 'VISIBLE';
        if (masked) delete actual.schema['x-query'].fields[field];
      }
      if (path === base + '/schema') {reads.push({kind: 'schema', resource: kind, mode: capturedMode}); respond(actual); return true;}
      if (path === providerBase + '/selected') {
        const ids = (url.searchParams.get('ids') ?? '').split(',');
        if (ids.length > 100 || ids.includes('***')) throw Error('Provider lookup is unbounded or uses masked IDs');
        reads.push({kind: 'selected', ids, mode: capturedMode});
        if (capturedMode === 'no-directory') {expectedFailure(403); respond({message: '接入目录不可读（隔离测试）'}, 403); return true;}
        respond(providers.filter(row => ids.includes(row.id)).map(({id, name, code, enabled}) => ({id, name, code, enabled}))); return true;
      }
      if (path === base && req.method === 'GET') {
        const page = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
        if (!Number.isSafeInteger(page) || page < 0 || !Number.isSafeInteger(size) || size < 1 || size > 20)
          throw Error('AI configuration must use bounded real pages, not a 500-item directory preload');
        if (url.searchParams.has('id')) throw Error('Provider directory does not declare ID query capability');
        reads.push({kind: 'list', resource: kind, mode: capturedMode, params: new URLSearchParams(url.searchParams)});
        if (kind === 'providers' && capturedMode === 'no-directory') {expectedFailure(403); respond({message: '接入目录不可读（隔离测试）'}, 403); return true;}
        const fields = Object.fromEntries(Object.entries(actual.schema['x-query'].fields).map(([field, value]) => [field,
          {type: value.javaType === 'Boolean' ? 'boolean' : value.javaType === 'BigDecimal' ? 'number' : value.javaType === 'Instant' ? 'date-time' : 'string'}]));
        const filters = Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort'].includes(key)));
        const query = compileLocalQuery(fields, {filters, sort: url.searchParams.getAll('sort')});
        const data = rows.filter(query.matches).sort(query.compare);
        let content = data.slice(page * size, (page + 1) * size);
        if (capturedMode === 'readonly') content = content.map(row => ({...row, ...Object.fromEntries(Object.entries(actual.schema.properties)
          .filter(([, property]) => property['x-field-access'] === 'MASKED').map(([field]) => [field, '***']))}));
        if (capturedMode === 'masked-id') content = content.map(row => ({...row, id: '***'}));
        respond({content, page: {number: page, size, totalElements: data.length, totalPages: Math.ceil(data.length / size)}}); return true;
      }
      const suffix = path.startsWith(base + '/') ? path.slice(base.length + 1) : '';
      const match = suffix.match(/^([^/]+)\/(test|models\/discover|models\/sync|debug\/stream)$/);
      if (match) {
        const id = decodeURIComponent(match[1]), action = {'models/discover': 'discover', 'models/sync': 'sync', 'debug/stream': 'debug', test: 'test'}[match[2]];
        const row = rows.find(row => row.id === id); if (!row) throw Error('AI action lost its real captured target');
        if (['no-external', 'masked-id'].includes(capturedMode) || capturedMode === 'test-only' && action !== 'test') throw Error('AI action fabricated a capability');
        if (row.enabled !== true || action === 'debug' && row.available !== true) throw Error('AI action used an unavailable target');
        let raw = ''; for await (const chunk of req) raw += chunk;
        const data = raw ? JSON.parse(raw) : undefined;
        if (action !== 'discover' && action !== 'debug' && (!data || Object.keys(data).length)) throw Error('Provider action fabricated input');
        const result = nextResult, held = nextHold; nextResult = 'success'; nextHold = false;
        const request = {id, action, data, result, mode: capturedMode, completed: false};
        (action === 'debug' ? generations : operations).push(request);
        if (held) await new Promise(resolve => {request.release = resolve;}); request.completed = true;
        if (result === 'error') {if (action !== 'discover') row.lastStatus = 'FAILED'; expectedFailure(400);
          respond({errorCode: action === 'test' ? 'AI_PROVIDER_CONNECTION_TEST_FAILED' : 'AI_PROVIDER_MODEL_SYNC_FAILED'}, 400); return true;}
        if (action === 'discover') {respond(result === 'malformed' ? {} : remote); return true;}
        if (action === 'test') {row.lastStatus = 'SUCCESS'; respond({providerId: result === 'foreign' ? 'other-provider' : id,
          success: result === 'malformed' ? 'true' : result !== 'false', discoveredModelCount: 521}); return true;}
        if (action === 'sync') {row.lastStatus = 'SYNCED'; respond({providerId: id, discovered: 521, created: 21, updated: 500, unavailable: 2}); return true;}
        const events = [{type: 'TEXT_DELTA', textDelta: '实际流响应：你好'}];
        if (result === 'stream-error') events.push({type: 'ERROR', errorCode: 'AI_MODEL_DEBUG_GENERATION_FAILED'});
        else if (result !== 'incomplete') events.push({type: 'COMPLETED', result: {output: [{type: 'TEXT', text: '实际流响应：你好'}]}});
        respondStream(events.map(event => `data: ${JSON.stringify(event)}\r\n\r\n`).join('')); return true;
      }
      if (path === base && ['POST', 'PUT'].includes(req.method)) {
        let raw = ''; for await (const chunk of req) raw += chunk; const data = JSON.parse(raw);
        writes.push({kind, method: req.method, data, mode: capturedMode});
        if (capturedMode === 'test-only') throw Error('AI editor fabricated a write capability');
        if (capturedMode === 'readonly' && Object.keys(data).some(field => !['id', 'description'].includes(field))) throw Error('AI editor resubmitted protected fields');
        if (['scopeType', 'tenantId', 'providerName', 'providerType', 'credentialCiphertext', 'hasApiKey', 'lastStatus', 'pricingAutoDetected', 'typeAutoDetected', 'discovered', 'available', 'metadataJson'].some(field => field in data))
          throw Error('AI editor leaked server-owned metadata');
        if (req.method === 'PUT') {
          const row = rows.find(row => row.id === data.id); if (!row) throw Error('AI edit lost captured ID');
          if ('apiKey' in data && !data.apiKey?.trim()) throw Error('Editing blank provider credential must be omitted');
          Object.assign(row, data); respond(row); return true;
        }
        if (kind === 'providers' ? !data.name?.trim() || !data.code?.trim() || !data.vendor : !data.providerId || !data.modelId?.trim() || !data.modelType)
          throw Error('Real SForm did not enforce genuine required fields');
        const row = {...data, id: `new-${kind}-${writes.length}`}; rows.push(row); respond(row); return true;
      }
      return false;
    },
  };
}
