import {readFileSync} from 'node:fs';
import {compileLocalQuery} from '../libs/components/DataTable/localQuery.ts';

export const storageCrudRoutes = [
  ['providers', '/smoke/system/object-storage/crud', 'OSS CRUD 测试'],
  ['quotas', '/smoke/platform/storage-quotas/crud', '配额 CRUD 测试'],
  ['objects', '/smoke/storage-objects/crud', '对象上传测试'],
].map(([key, path, label]) => ({id: `storage-crud-${key}`, code: 'common.view', type: 'PAGE', routeKind: 'item',
  component: 'common/platform/ObjectStorage', path, label, scopeTypes: ['PLATFORM']}));

/** Real exported schema fields plus the independent action grants verified by StorageActionsTest. */
export function createStorageCrudFixture() {
  const base = '/common/platform/object-storage';
  const schemas = Object.fromEntries(['provider-configs', 'quotas', 'objects'].map(section => [section,
    JSON.parse(readFileSync(new URL(`./storage-schemas/${section}.json`, import.meta.url), 'utf8'))]));
  const action = (key, title, sort) => ({key, title, sort, argumentMinSize: ['add', 'upload'].includes(key) ? 0 : 1,
    argumentMaxSize: ['add', 'upload'].includes(key) ? 0 : key === 'delete' ? 10 : 1, danger: key === 'delete'});
  const actions = {
    'provider-configs': [action('add', '新增 OSS 配置', 0), action('edit', '编辑', 1), action('delete', '删除', 2), action('test', '测试连接', 3)],
    quotas: [action('add', '新增配额', 0), action('edit', '编辑', 1), action('delete', '删除', 2)],
    objects: [action('upload', '上传对象', 0), action('view', '详情', 1), action('download', '下载', 2), action('delete', '删除', 3)],
  };
  const rows = {
    'provider-configs': [{id: 'storage-provider-old', code: 'fixture-provider', name: '原配置', type: 'S3',
      region: 'us-east-1', accessKey: 'fixture-access', bucket: 'fixture', enabled: true, defaultProvider: false,
      secretConfigured: true, endpoint: null, basePath: null, publicBaseUrl: null, description: '原说明'}],
    quotas: [{id: 'storage-quota-old', tenantId: 'tenant-001', tenantName: 'Tenant001', quotaBytes: 12345,
      usedBytes: 10, remainingBytes: 12335, enabled: true, description: '原配额'}],
    objects: [],
  };
  let enabled = false, readonly = false, failNext = false, sequence = 0;
  const requests = [], writes = [];
  return {rows, requests, writes, enable() {enabled = true;}, disable() {enabled = false;},
    setReadonly(value) {readonly = value;}, failWrite() {failNext = true;},
    async handle(req, url, respond, expectedFailure) {
      if (!enabled || !(url.pathname.startsWith(base) || url.pathname === '/common/object-storage/upload')) return false;
      requests.push({path: url.pathname, method: req.method, params: new URLSearchParams(url.searchParams)});
      if (url.pathname === '/common/object-storage/upload') {
        let body = ''; for await (const chunk of req) body += chunk;
        writes.push({path: url.pathname, contentType: req.headers['content-type'], body});
        await new Promise(resolve => setTimeout(resolve, 250));
        const row = {id: `uploaded-${++sequence}`, providerCode: 'fixture-provider', bucket: 'fixture',
          originalFileName: 'fixture.txt', objectKey: 'folder/fixture.txt', contentLength: 3};
        rows.objects.push(row); respond(row); return true;
      }
      const match = /^\/(provider-configs|quotas|objects)(?:\/(.*))?$/.exec(url.pathname.slice(base.length));
      if (!match) throw Error(`Unexpected storage fixture endpoint ${url.pathname}`);
      const [_, section, suffix] = match;
      if (suffix === 'schema') {
        respond({...schemas[section], buttons: readonly ? section === 'objects' ? actions.objects.filter(action =>
          ['view', 'download'].includes(action.key)) : [] : actions[section]}); return true;
      }
      if (req.method !== 'GET') {
        let body = ''; for await (const chunk of req) body += chunk;
        const data = body ? JSON.parse(body) : null;
        writes.push({path: url.pathname, method: req.method, data, params: new URLSearchParams(url.searchParams)});
        await new Promise(resolve => setTimeout(resolve, 250));
        if (failNext) {failNext = false; expectedFailure(503); respond({message: 'OSS 保存失败（测试）'}, 503); return true;}
        if (suffix?.endsWith('/test')) {respond({success: true}); return true;}
        if (req.method === 'DELETE') {
          const ids = new Set(url.searchParams.get('ids').split(',')); rows[section] = rows[section].filter(row => !ids.has(row.id));
          respond([...ids]); return true;
        }
        const current = data.id && rows[section].find(row => row.id === data.id);
        const record = {...current, ...data, id: data.id || `created-${++sequence}`};
        delete record.secretKey; delete record.secretKeyCiphertext; // Credential inputs are never list values.
        if (section === 'provider-configs') record.secretConfigured = true;
        if (current) Object.assign(current, record); else rows[section].push(record);
        respond(record); return true;
      }
      if (suffix) {respond(rows[section].find(row => row.id === suffix)); return true;}
      const size = Number(url.searchParams.get('size') ?? 20), page = Number(url.searchParams.get('page') ?? 0);
      if (size < 1 || size > 100) throw Error('Unbounded storage CRUD page');
      const fields = Object.fromEntries(Object.entries(schemas[section].schema['x-query'].fields).map(([key, value]) =>
        [key, {type: value.javaType === 'Boolean' ? 'boolean' : value.javaType === 'Long' ? 'integer' : 'string'}]));
      const filters = Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort'].includes(key)));
      const query = compileLocalQuery(fields, {filters, sort: url.searchParams.getAll('sort')});
      const result = rows[section].filter(query.matches).sort(query.compare);
      respond({content: result.slice(page * size, (page + 1) * size),
        page: {number: page, size, totalElements: result.length, totalPages: Math.ceil(result.length / size)}}); return true;
    }};
}
