import {readFileSync} from 'node:fs';
import {compileLocalQuery} from '../libs/components/DataTable/localQuery.ts';

// Generated/compared by CommonMainTableQueryHttpTest: never a parallel schema or form provider.
const schema = JSON.parse(readFileSync(new URL('./common-main-schemas/SystemNotification.json', import.meta.url), 'utf8'));
const base = '/common/system/notifications';
const page = (rows, url, maximum = 100) => {
  const number = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
  if (!Number.isSafeInteger(number) || number < 0 || !Number.isSafeInteger(size) || size < 1 || size > maximum) throw Error('Unbounded notification or audience page');
  return {content: rows.slice(number * size, (number + 1) * size), page: {number, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}};
};
export function createNotificationLifecycleFixture() {
  const rows = Array.from({length: 521}, (_, index) => ({id: 'notification-' + String(index).padStart(3, '0'),
    title: 'Notice ' + String(index).padStart(3, '0'), content: 'Actual notification content', category: 'SYSTEM', priority: 'NORMAL',
    audienceType: 'ALL', audienceId: null, status: ['DRAFT', 'PUBLISHED', 'REVOKED'][index % 3], publishAt: null, expireAt: null}));
  rows[520].status = 'DRAFT'; rows[520].publishAt = '***';
  const users = Array.from({length: 521}, (_, index) => ({id: 'notification-user-' + index, name: 'Recipient' + index,
    email: 'recipient' + index + '@example.test', phoneNumber: null}));
  const tenants = Array.from({length: 521}, (_, index) => ({id: 'notification-tenant-' + index, name: 'AudienceTenant' + index}));
  const fields = Object.fromEntries(Object.entries(schema.schema['x-query'].fields).map(([name, definition]) => [name,
    {type: definition.javaType === 'Instant' ? 'date-time' : 'string'}]));
  const reads = [], writes = [];
  let enabled = false, mode = 'all', failure = 0, directoryDenied = false, writeDelay = 500, nextId = 0;
  return {rows, users, tenants, reads, writes, enable() {enabled = true;}, mode(value) {mode = value;},
    failWrite(status = 503) {failure = status;}, directoryDenied(value) {directoryDenied = value;}, writeDelay(value) {writeDelay = value;},
    async handle(req, url, respond, expectedFailure) {
      if (!enabled) return false;
      const path = url.pathname, currentMode = mode;
      if (path === '/common/platform/dictionaries/options') {
        const values = {'notification.category': ['ANNOUNCEMENT', 'SYSTEM', 'MAINTENANCE', 'SECURITY'],
          'notification.priority': ['LOW', 'NORMAL', 'HIGH', 'URGENT'], 'notification.audience-type': ['ALL', 'TENANT', 'USER'],
          'notification.status': ['DRAFT', 'PUBLISHED', 'REVOKED']}[url.searchParams.get('dictionaryCode')];
        if (!values) return false; respond(values.map(value => ({value, label: value}))); return true;
      }
      if (path === base + '/schema') {
        const actual = structuredClone(schema);
        if (currentMode === 'viewer') actual.buttons = [];
        if (currentMode === 'no-edit') actual.buttons = actual.buttons.filter(button => button.key !== 'edit');
        if (currentMode === 'readonly-audience') actual.schema.properties.audienceId.readOnly = true;
        if (currentMode === 'masked') actual.schema.properties.status['x-field-access'] = 'MASKED';
        respond(actual); return true;
      }
      if (req.method === 'GET' && path === base) {
        reads.push({kind: 'list', mode: currentMode, params: new URLSearchParams(url.searchParams)});
        const filters = Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort'].includes(key)));
        const query = compileLocalQuery(fields, {filters, sort: url.searchParams.getAll('sort')});
        const result = rows.filter(query.matches).sort(query.compare);
        const response = page(result, url); if (currentMode === 'masked') response.content = response.content.map(row => ({...row, id: '***', status: '***'}));
        respond(response); return true;
      }
      if (path.startsWith('/common/users/picker/') || path === '/common/platform/tenants') {
        if (req.method !== 'GET') throw Error('Audience selection must not mutate accounts or tenants');
        const user = path.startsWith('/common/users/'), selected = path.endsWith('/selected');
        const read = {kind: user ? 'user' : 'tenant', selected, mode: currentMode, params: new URLSearchParams(url.searchParams)}; reads.push(read);
        if (directoryDenied) {expectedFailure(403); respond({message: '无受众目录查看权限（测试）'}, 403); return true;}
        if (selected) {
          const ids = url.searchParams.get('ids').split(','); if (ids.length > 100) throw Error('Unbounded audience hydration');
          respond(users.filter(row => ids.includes(row.id))); return true;
        }
        if (user) {
          const keyword = url.searchParams.get('keyword') ?? ''; if (keyword.trim().length < 3) throw Error('User audience ignores minimum prefix');
          respond(page(users.filter(row => row.email.startsWith(keyword)), url, 50)); return true;
        }
        const id = url.searchParams.get('id'), name = url.searchParams.get('name');
        const query = compileLocalQuery({id: {type: 'string'}, name: {type: 'string'}}, {filters: { ...(id ? {id} : {}), ...(name ? {name} : {}) }});
        respond(page(tenants.filter(query.matches), url, 100)); return true;
      }
      const match = path.startsWith(base + '/') ? path.slice(base.length + 1).match(/^([^/]+)\/(publish|revoke|duplicate)$/) : null;
      if (!match && !(path === base && ['POST', 'PUT', 'DELETE'].includes(req.method))) return false;
      let raw = ''; for await (const chunk of req) raw += chunk;
      const data = raw ? JSON.parse(raw) : null, action = match?.[2] ?? req.method;
      const id = match ? decodeURIComponent(match[1]) : data?.id;
      const target = rows.find(row => row.id === id), write = {action, id, data, mode: currentMode, completed: false}; writes.push(write);
      if (currentMode === 'viewer' || currentMode === 'masked' || currentMode === 'no-edit' && action === 'PUT') throw Error('Notification forged a server action grant');
      if (match && (!target || Object.keys(data).length)) throw Error('Lifecycle lost its captured identity or invented a mutation command');
      const failed = failure; failure = 0; await new Promise(resolve => setTimeout(resolve, writeDelay)); write.completed = true;
      if (failed) {expectedFailure(failed); respond({message: failed === 400 ? '通知状态已改变（测试）' : '通知事务失败（测试）'}, failed); return true;}
      if (action === 'publish') {if (target.status !== 'DRAFT') throw Error('Only real drafts publish'); Object.assign(target, {status: 'PUBLISHED', publishAt: new Date().toISOString()}); respond(target);}
      else if (action === 'revoke') {if (target.status !== 'PUBLISHED') throw Error('Only real published notifications revoke'); target.status = 'REVOKED'; respond(target);}
      else if (action === 'duplicate') {
        if (target.status !== 'REVOKED') throw Error('Only real revoked notifications duplicate');
        const copy = {...target, id: 'notification-copy-' + nextId++, status: 'DRAFT', publishAt: null}; rows.push(copy); respond(copy);
      } else if (action === 'PUT') {
        if (!target || target.status !== 'DRAFT' || currentMode === 'readonly-audience' && data.audienceId !== target.audienceId) throw Error('Metadata update lost its captured draft/readonly audience');
        if (['TENANT', 'USER'].includes(data.audienceType) && !data.audienceId) throw Error('Targeted notification lacks a real selected audience');
        Object.assign(target, data); respond(target);
      } else if (action === 'POST') {
        if (['TENANT', 'USER'].includes(data.audienceType) && !data.audienceId) throw Error('Created targeted notification lacks audience');
        const created = {...data, id: 'notification-new-' + nextId++, status: 'DRAFT', publishAt: null}; rows.push(created); respond(created);
      } else {
        for (const selectedId of (url.searchParams.get('ids') ?? '').split(',')) {
          const index = rows.findIndex(row => row.id === selectedId); if (index < 0 || !['DRAFT', 'REVOKED'].includes(rows[index].status)) throw Error('Invalid notification deletion');
          rows.splice(index, 1);
        }
        respond(null);
      }
      return true;
    }};
}
