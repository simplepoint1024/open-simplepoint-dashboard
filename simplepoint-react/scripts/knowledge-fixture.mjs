import {compileLocalQuery, localQuerySchema} from '../libs/components/DataTable/localQuery.ts';

export const knowledgeRoute = {id: 'knowledge-lifecycle', code: 'ai.workbench.knowledge-bases.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/knowledge-lifecycle', component: 'ai/workbench/KnowledgeBases', label: '知识库生命周期测试', scopeTypes: ['PLATFORM']};
const base = '/ai/workbench/knowledge-bases';
const fields = {id: {type: 'string'}, name: {type: 'string'}, code: {type: 'string'}, embeddingModelId: {type: 'string'},
  retrievalMode: {type: 'string'}, enabled: {type: 'boolean'}, documentCount: {type: 'integer'}, chunkCount: {type: 'integer'}};
const schema = localQuerySchema(fields); schema.required = ['name', 'code', 'embeddingModelId'];
Object.values(schema.properties).forEach(property => {property['x-ui'] = {'x-list-visible': true};});
const page = (rows, url) => {
  const number = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
  if (!Number.isInteger(size) || size < 1 || size > 100) throw Error('Knowledge reads must use bounded pages');
  return {content: rows.slice(number * size, (number + 1) * size), page: {number, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}};
};
const filter = (rows, fields, url) => {
  const query = compileLocalQuery(fields, {filters: Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort'].includes(key))), sort: url.searchParams.getAll('sort')});
  return rows.filter(query.matches).sort(query.compare);
};

export function createKnowledgeFixture() {
  const bases = Array.from({length: 521}, (_, index) => ({id: 'kb-' + index, name: 'Knowledge' + String(index).padStart(3, '0'),
    code: 'kb-' + index, embeddingModelId: 'embedding-520', retrievalMode: 'HYBRID', enabled: true, documentCount: index === 0 ? 521 : 2, chunkCount: 521}));
  const documents = new Map(bases.map(kb => [kb.id, Array.from({length: kb.documentCount}, (_, index) => ({id: kb.id + '-document-' + index,
    name: 'Document' + String(index).padStart(3, '0'), sourceType: 'TEXT', status: 'READY', fileSize: 2048, chunkCount: 1, createdAt: new Date(Date.UTC(2026, 0, 1, 0, 0, 521 - index)).toISOString()}))]));
  const models = Array.from({length: 521}, (_, index) => ({id: 'embedding-' + index, name: 'Embedding' + String(index).padStart(3, '0')}));
  documents.get('kb-0')[0].fileSize = '***';
  const requests = [], writes = []; let active = false, mode = 'all', failure, readFailure;
  return {requests, writes, documents, bases, models, enable() {active = true;}, mode(value) {mode = value;},
    failWrite(path) {failure = path;}, failRead(path) {readFailure = path;},
    async handle(req, url, respond, expectedFailure) {
      const path = url.pathname; if (!active || !(path === base || path.startsWith(base + '/'))) return false;
      requests.push({path, method: req.method, params: new URLSearchParams(url.searchParams)});
      if (req.method === 'GET') {
        if (readFailure === path || mode === 'denied' && path === base + '/schema') {
          readFailure = undefined; expectedFailure(503); return respond({message: '知识库读取失败（测试）'}, 503), true;
        }
        if (path === base + '/schema') {
          const buttons = [
            ...(['all', 'config'].includes(mode) ? [{key: 'add', title: '新增', sort: 0, argumentMinSize: 0, argumentMaxSize: 0},
              {key: 'edit', title: '编辑', sort: 1, argumentMinSize: 1, argumentMaxSize: 1}] : []),
            ...(['all', 'documents'].includes(mode) ? [{key: 'documents', title: '文档管理', sort: 2, argumentMinSize: 1, argumentMaxSize: 1}] : []),
            ...(['all', 'retrieve'].includes(mode) ? [{key: 'retrieve', title: '检索测试', sort: 3, argumentMinSize: 1, argumentMaxSize: 1}] : []),
          ];
          return respond({schema, buttons}), true;
        }
        if (path === base) return respond(page(filter(bases, fields, url), url)), true;
        if (path === base + '/embedding-model-options') return respond(page(filter(models, {id: {type: 'string'}, name: {type: 'string'}}, url), url)), true;
        if (path === base + '/embedding-models') throw Error('Knowledge UI must not load the legacy all-model endpoint');
        if (path === base + '/document-limits') {
          if (!['all', 'documents'].includes(mode)) throw Error('Ingestion limits require actual documents authority');
          return respond({maxUploadBytes: 65536}), true;
        }
        const match = path.match(/\/knowledge-bases\/(kb-\d+)\/documents$/);
        if (match) {
          if (!['all', 'documents'].includes(mode)) throw Error('Retrieve/view permission does not imply document access');
          return respond(page(filter(documents.get(match[1]), {createdAt: {type: 'date-time'}}, url), url)), true;
        }
        throw Error('Unknown knowledge fixture read ' + path);
      }
      const target = path.match(/\/knowledge-bases\/(kb-\d+)\/(documents(?:\/.+)?|retrieve)$/);
      if (!target) throw Error('Unexpected KB configuration mutation in lifecycle fixture');
      const [, id, operation] = target;
      if (!bases.some(kb=>kb.id===id) || !['all', operation === 'retrieve' ? 'retrieve' : 'documents'].includes(mode)) throw Error('Mutation requires captured KB and independent authority');
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const body = Buffer.concat(chunks), multipart = req.headers['content-type']?.startsWith('multipart/form-data');
      const data = !multipart && body.length ? JSON.parse(body.toString()) : undefined;
      if (operation === 'documents/text' && (!data.name.trim() || !data.content.trim())) throw Error('SForm must reject blank document text');
      if (multipart && (!req.headers['content-type'].includes('boundary=') || !body.includes(Buffer.from('filename="actual.txt"')) || !body.includes(Buffer.alloc(2048)))) throw Error('Upload must retain real binary and multipart transport');
      const write = {path, id, operation, method: req.method, data, body, params: new URLSearchParams(url.searchParams), completed: false};
      writes.push(write); await new Promise(resolve=>setTimeout(resolve, 350)); write.completed = true;
      if (failure === path) {failure = undefined; expectedFailure(503); return respond({message: '知识库写入失败（测试）'}, 503), true;}
      if (operation === 'retrieve') return respond({query: data.query, mode: data.mode, hits: [{chunkId: 'actual-chunk', documentId: id + '-document-0', documentName: 'Actual Retrieved Document', content: 'Actual matched content', score: '***'}]}), true;
      if (req.method === 'DELETE') {
        const captured = url.searchParams.get('ids'), rows = documents.get(id), index = rows.findIndex(row=>row.id===captured);
        if (index < 0) throw Error('Delete lost exact captured document'); rows.splice(index, 1); return respond([captured]), true;
      }
      if (operation.endsWith('/reindex')) {
        const captured = operation.split('/')[1], row = documents.get(id).find(row=>row.id===captured);
        if (!row) throw Error('Reindex lost captured document'); return respond(row), true;
      }
      const row = {id: id + '-document-created-' + writes.length, name: data?.name ?? 'actual.txt', sourceType: multipart ? 'UPLOAD' : 'TEXT',
        status: 'READY', fileSize: 2048, chunkCount: 1, createdAt: new Date().toISOString()};
      documents.get(id).unshift(row); return respond(row), true;
    }};
}
