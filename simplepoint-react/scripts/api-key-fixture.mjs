import {readFileSync} from 'node:fs';
import {compileLocalQuery, localQuerySchema} from '../libs/components/DataTable/localQuery.ts';

export const apiKeyRoute = {id: 'api-key-lifecycle', code: 'ai.workbench.api-keys.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/api-key-lifecycle', component: 'ai/workbench/ApiKeys', label: 'API Key 生命周期测试', scopeTypes: ['PLATFORM']};
const base = '/ai/workbench/api-keys', dictionaryBase = '/common/platform/dictionaries';
const fields = {id: {type: 'string'}, name: {type: 'string'}, scopeType: {type: 'string'}, keyPrefix: {type: 'string'},
  enabled: {type: 'boolean'}, rateLimitPerMinute: {type: 'integer'}, expiresAt: {type: 'date-time'}, lastUsedAt: {type: 'date-time'},
  usageCount: {type: 'integer'}, description: {type: 'string'}};
const schema = localQuerySchema(fields); schema.required = ['name'];
schema.properties.name.minLength = 1; schema.properties.name.maxLength = 128;
Object.values(schema.properties).forEach(property => {property['x-ui'] = {'x-list-visible': true};});
schema.properties.enabled.default = true;
Object.assign(schema.properties.rateLimitPerMinute, {minimum: 1, maximum: 100000});
const dictionarySchema = JSON.parse(readFileSync(new URL('./common-main-schemas/Dictionary.json', import.meta.url), 'utf8'));
const page = (rows, url, fields) => {
  const number = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
  if (!Number.isInteger(size) || size < 1 || size > 100) throw Error('API Key reads must use real bounded pages');
  const query = compileLocalQuery(fields, {filters: Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort'].includes(key))), sort: url.searchParams.getAll('sort')});
  const result = rows.filter(query.matches).sort(query.compare);
  return {content: result.slice(number * size, (number + 1) * size), page: {number, size, totalElements: result.length, totalPages: Math.ceil(result.length / size)}};
};

/** Isolated dummy issuance only. Raw values are response-only, never list rows or persisted preferences. */
export function createApiKeyFixture() {
  const rows = Array.from({length: 521}, (_, index) => ({id: 'api-key-' + index, name: 'Key' + String(index).padStart(3, '0'),
    scopeType: 'SYSTEM', keyPrefix: 'spk_public_' + index, enabled: index !== 0, rateLimitPerMinute: 17,
    expiresAt: '2030-01-01T00:00:00Z', lastUsedAt: null, usageCount: index, description: 'Actual policy'}));
  const dictionaries = [{id: 'dict-scope-0', name: 'Actual dictionary', code: 'scope-dictionary', enabled: true, sort: 0, description: 'Original'}];
  let active = false, mode = 'all', dictionaryMode = 'all', failure, validationFailure, sequence = 0;
  const requests = [], writes = [];
  return {rows, dictionaries, requests, writes, enable() {active = true;}, mode(value) {mode = value;}, dictionaryMode(value) {dictionaryMode = value;},
    failWrite(method, path) {failure = method + ':' + path;},
    rejectValidationOnce() {validationFailure = true;},
    async handle(req, url, respond, expectedFailure) {
      const path = url.pathname; if (!active || ![base, dictionaryBase].some(prefix=>path === prefix || path.startsWith(prefix + '/'))) return false;
      requests.push({path, method: req.method, params: new URLSearchParams(url.searchParams), context: req.headers['x-context-id']});
      const isDictionary = path.startsWith(dictionaryBase), currentMode = isDictionary ? dictionaryMode : mode;
      if (req.method === 'GET') {
        if (path === base + '/schema') {
          if (mode === 'denied') {expectedFailure(503); return respond({errorCode: 'AI_API_KEY_OPERATION_FAILED'}, 503), true;}
          const current = structuredClone(schema);
          if (mode === 'fields') {
            Object.assign(current.properties.name, {readOnly: true, 'x-field-access': 'VISIBLE'});
            for (const field of ['enabled', 'expiresAt', 'rateLimitPerMinute']) Object.assign(current.properties[field], {readOnly: true, 'x-field-access': 'MASKED'});
          }
          return respond({schema: current, buttons: [
            ...(mode === 'all' ? [{key: 'add', title: '新增', sort: 0, argumentMinSize: 0, argumentMaxSize: 1}, {key: 'delete', title: '删除', sort: 3, argumentMinSize: 1, argumentMaxSize: 10}] : []),
            ...(['all', 'fields'].includes(mode) ? [{key: 'edit', title: '编辑', sort: 1, argumentMinSize: 1, argumentMaxSize: 1}] : []),
            ...(['all', 'rotate', 'masked', 'identity'].includes(mode) ? [{key: 'rotate', title: '轮换 Key', sort: 2, argumentMinSize: 1, argumentMaxSize: 1}] : []),
          ]}), true;
        }
        if (path === base) return respond(page(rows.map(row=>({...row,
          ...(row.id === 'api-key-1' ? {enabled: '***', expiresAt: '***', lastUsedAt: '***', scopeType: '***'} : {}),
          ...(mode === 'fields' && row.id === 'api-key-0' ? {enabled: '***', expiresAt: '***', rateLimitPerMinute: '***'} : {}),
          ...(mode === 'identity' ? {id: '***'} : {}),
        })), url, fields)), true;
        if (path === dictionaryBase + '/schema') return respond({...dictionarySchema,
          buttons: dictionaryMode === 'view' ? [] : dictionarySchema.buttons.filter(button=>button.key !== 'config.item')}), true;
        if (path === dictionaryBase) return respond(page(dictionaries, url, {name: {type: 'string'}, code: {type: 'string'}, enabled: {type: 'boolean'}, sort: {type: 'integer'}, description: {type: 'string'}})), true;
        throw Error('Unknown isolated key/scope fixture read ' + path);
      }
      if (['view', 'denied', 'identity'].includes(currentMode)) throw Error('No current authority for API key/default CRUD write');
      let raw = ''; for await (const chunk of req) raw += chunk;
      const data = raw ? JSON.parse(raw) : undefined;
      if (!isDictionary && data && Object.keys(data).some(key=>!['id', 'name', 'enabled', 'description', 'expiresAt', 'rateLimitPerMinute'].includes(key))) throw Error('API Key policy writes must never resend secrets or projected readonly metadata');
      const write = {path, method: req.method, data, params: new URLSearchParams(url.searchParams), context: req.headers['x-context-id'], mode: currentMode, completed: false};
      writes.push(write); const fail = failure === req.method + ':' + path; if (fail) failure = undefined;
      await new Promise(resolve=>setTimeout(resolve, 450)); write.completed = true;
      if (isDictionary && validationFailure) {
        validationFailure = false; expectedFailure(400);
        return respond({code: 'VALIDATION_FAILED', messageKey: 'form.validation.failed',
          fieldErrors: [{field: 'code', keyword: 'pattern', params: {}}]}, 400), true;
      }
      if (fail) {expectedFailure(503); return respond(isDictionary ? {message: '默认 CRUD 写入失败（测试）'} : {errorCode: 'AI_API_KEY_OPERATION_FAILED'}, 503), true;}
      if (isDictionary) {
        if (req.method === 'DELETE') {
          const ids = url.searchParams.get('ids').split(','); for (const id of ids) {const index = dictionaries.findIndex(row=>row.id===id); if (index >= 0) dictionaries.splice(index, 1);}
          return respond(ids), true;
        }
        const current = dictionaries.find(row=>row.id===data.id);
        if (current) Object.assign(current, data); else dictionaries.push({...data, id: 'dict-created-' + ++sequence});
        return respond(current ?? dictionaries.at(-1)), true;
      }
      const rotate = path.match(/\/api-keys\/(api-key-\d+|key-created-\d+)\/rotate$/);
      if (rotate) {
        if (!['all', 'rotate', 'masked'].includes(currentMode)) throw Error('Rotation requires its own grant');
        const row = rows.find(row=>row.id===rotate[1]); if (!row) throw Error('Rotation lost its captured key');
        row.keyPrefix = 'spk_rotated_' + ++sequence; row.usageCount = 0;
        const issuedKey = currentMode === 'masked' ? '***' : 'isolated-key-rotation-' + sequence;
        write.issuedKey = issuedKey; return respond({...row, issuedKey}), true;
      }
      if (req.method === 'DELETE') {
        if (currentMode !== 'all') throw Error('Rotate/edit permission does not permit revocation');
        const ids = url.searchParams.get('ids').split(','); for (const id of ids) {const index = rows.findIndex(row=>row.id===id); if (index >= 0) rows.splice(index, 1);}
        return respond(ids), true;
      }
      if (path !== base) throw Error('Unexpected API key write');
      if (req.method === 'PUT') {
        if (!['all', 'fields'].includes(currentMode)) throw Error('Rotate permission does not permit policy edit');
        if (currentMode === 'fields' && Object.keys(data).some(key=>!['id', 'description'].includes(key))) throw Error('Metadata edit cannot overwrite masked policy or readonly name');
        const row = rows.find(row=>row.id===data.id); if (!row) throw Error('Metadata update lost captured key'); Object.assign(row, data); return respond(row), true;
      }
      if (currentMode !== 'all' || !data.name.trim()) throw Error('Issuance needs create grant and nonblank name');
      const row = {...data, id: 'key-created-' + ++sequence, keyPrefix: 'spk_created_' + sequence, scopeType: 'SYSTEM', usageCount: 0};
      rows.unshift(row); const issuedKey = 'isolated-key-create-' + sequence; write.issuedKey = issuedKey; return respond({...row, issuedKey}), true;
    }};
}
