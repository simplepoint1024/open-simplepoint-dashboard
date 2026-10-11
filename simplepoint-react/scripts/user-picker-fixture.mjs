import {readFileSync} from 'node:fs';

const tenantBase = '/common/platform/tenants', pickerBase = '/common/users/picker';
const tenantSchema = JSON.parse(readFileSync(new URL('./tenant-readonly-owner-schema.fixture.json', import.meta.url), 'utf8'));
const memberSchema = JSON.parse(readFileSync(new URL('./tenant-user-picker-schema.fixture.json', import.meta.url), 'utf8'));
const page = (rows, number, size) => ({content: rows.slice(number * size, (number + 1) * size),
  page: {number, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}});
const wait = milliseconds => new Promise(resolve=>setTimeout(resolve, milliseconds));

/** Real generated single/array field contracts mounted by the existing platform form; all data and writes are isolated. */
export function createUserPickerFixture() {
  const users = Array.from({length: 521}, (_, index)=>({id: 'user-' + String(index).padStart(3, '0'),
    name: 'Candidate' + String(index).padStart(3, '0'), email: 'picker' + index + '@example.test', phoneNumber: '138' + String(index).padStart(8, '0')}));
  const reads = [], writes = [];
  let active = false, mode = 'all', failure, lookupDelay = 0, searchDelay;
  let tenant = {id: 'picker-tenant', name: 'Picker tenant', tenantType: 'ORGANIZATION', ownerId: users[520].id,
    ownerName: 'Actual owner', description: 'Actual metadata'};
  return {users, reads, writes, enable() {active = true;}, tenant: () => tenant,
    mode(next) {mode = next; tenant = {...tenant, ownerId: next.startsWith('multi') ? null : users[520].id,
      userIds: next === 'multi-large' || next === 'multi-delayed' ? users.map(row=>row.id) : next === 'multi-edit' ? [users[0].id, users[520].id] : undefined};},
    failRead(kind) {failure = kind;}, delayLookup(milliseconds) {lookupDelay = milliseconds;},
    delaySearch(keyword, milliseconds) {searchDelay = {keyword, milliseconds};},
    async handle(req, url, respond, expectedFailure) {
      if (!active) return false;
      const path = url.pathname, currentMode = mode;
      if (path === '/common/platform/dictionaries/options' && url.searchParams.get('dictionaryCode') === 'tenant.type') {
        respond([{value: 'PERSONAL', label: '个人'}, {value: 'ORGANIZATION', label: '组织'}]); return true;
      }
      if (path === tenantBase + '/schema') {
        const schema = structuredClone(tenantSchema);
        if (currentMode === 'all' || currentMode === 'scope-b' || currentMode === 'required') {
          delete schema.schema.properties.ownerId.readOnly; schema.schema.properties.ownerId['x-field-access'] = 'EDITABLE';
        }
        if (currentMode === 'required') schema.schema.required = ['ownerId'];
        // Array tests reuse the actual DTO field; only test constraints are added, never a parallel widget or provider.
        if (currentMode.startsWith('multi')) schema.schema.properties.userIds = {...structuredClone(memberSchema.properties.userIds),
          ...(currentMode !== 'multi-edit' ? {readOnly: true} : {maxItems: 3})};
        respond(schema); return true;
      }
      if (path === tenantBase) {
        if (req.method === 'GET') {respond(page([tenant], 0, 20)); return true;}
        if (req.method !== 'PUT') throw Error('Picker fixture only grants existing metadata editing');
        let raw = ''; for await (const chunk of req) raw += chunk;
        const data = JSON.parse(raw); if (data.id !== tenant.id) throw Error('Lost captured picker form target');
        writes.push({data, mode: currentMode}); Object.assign(tenant, data); respond(tenant); return true;
      }
      if (!path.startsWith(pickerBase + '/')) return false;
      if (req.method !== 'GET') throw Error('Directory selection cannot write accounts');
      const kind = path.endsWith('/selected') ? 'lookup' : 'search';
      const read = {path, kind, params: new URLSearchParams(url.searchParams), mode: currentMode, completed: false, start: performance.now()};
      reads.push(read);
      const fail = failure === kind; if (fail) failure = undefined;
      if (kind === 'lookup') {
        const ids = url.searchParams.get('ids')?.split(',') ?? [];
        if (!ids.length || ids.length > 100 || url.search.length > 2501) throw Error('Hydration lost bounded CSV/URL contract');
        await wait(lookupDelay);
        read.completed = true; read.end = performance.now();
        if (fail || currentMode === 'denied') {const status = currentMode === 'denied' ? 403 : 503; expectedFailure(status); respond({message: '已选用户读取失败（测试）'}, status); return true;}
        const rows = ids.flatMap(id=>users.find(row=>row.id === id) ?? []);
        // Different scope resolves the same real IDs to its authorized labels, not yesterday's global cache.
        respond(rows.map(row=>({...row, ...(currentMode === 'scope-b' ? {name: 'New scope ' + row.name} : {})}))); return true;
      }
      const number = Number(url.searchParams.get('page')), size = Number(url.searchParams.get('size')), keyword = url.searchParams.get('keyword') ?? '';
      if (!Number.isInteger(number) || number < 0 || !Number.isInteger(size) || size < 1 || size > 50 || keyword.trim().length < 3) throw Error('Search lost bounded/minimum-prefix protocol');
      if (searchDelay?.keyword === keyword) await wait(searchDelay.milliseconds);
      read.completed = true; read.end = performance.now();
      if (fail || currentMode === 'denied') {const status = currentMode === 'denied' ? 403 : 503; expectedFailure(status); respond({message: '用户搜索失败（测试）'}, status); return true;}
      const prefix = keyword.trim().toLowerCase();
      respond(page(users.filter(row=>row.email.toLowerCase().startsWith(prefix) || row.phoneNumber.startsWith(prefix)), number, size)); return true;
    }};
}
