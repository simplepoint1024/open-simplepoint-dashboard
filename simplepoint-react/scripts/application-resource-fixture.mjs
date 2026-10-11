import {readFileSync} from 'node:fs';

export const applicationResourceRoute = {id: 'application-resource-test', code: 'common.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/application-resources', component: 'common/platform/Application', label: '应用资源配置测试', scopeTypes: ['PLATFORM']};
const base = '/common/platform/applications', resourcesBase = '/common/resources';
const schema = JSON.parse(readFileSync(new URL('./application-resource-root-schema.fixture.json', import.meta.url), 'utf8'));
const directoryPages = new Map([0, 26].map(number => [number, JSON.parse(readFileSync(
  new URL('./application-resource-contracts/root-page-' + number + '.json', import.meta.url), 'utf8'))]));
const assignedPages = new Map([0, 25].map(number => [number, JSON.parse(readFileSync(
  new URL('./application-resource-contracts/assigned-page-' + number + '.json', import.meta.url), 'utf8'))]));
const subtreeCodes = JSON.parse(readFileSync(new URL('./application-resource-contracts/subtree-codes.json', import.meta.url), 'utf8'));
const wait = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const body = async req => {let raw = ''; for await (const chunk of req) raw += chunk; return JSON.parse(raw);};
const page = (rows, url) => {
  const number = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
  if (!Number.isSafeInteger(number) || number < 0 || size !== 20 && size !== 10) throw Error('Resource configuration lost bounded paging');
  return {content: rows.slice(number * size, (number + 1) * size), page: {number, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}};
};

/** Native HTTP root schema, first/tail directory pages and complete subtree codes; mutations remain isolated. */
export function createApplicationResourceFixture() {
  const applications = [{id: 'resource-app-a', code: 'resource-app-a', name: 'Resource Alpha', enabled: true},
    {id: 'resource-app-b', code: 'resource-app-b', name: 'Resource Beta', enabled: true}];
  const roots = Array.from({length: 521}, (_, i) => ({id: 'resource-' + String(i).padStart(3, '0'), code: 'resource.code.' + String(i).padStart(3, '0'),
    name: 'Resource' + String(i).padStart(3, '0'), type: i === 0 ? 'GROUP' : 'PAGE', hasChildren: i === 0,
    grantable: i !== 0, disabled: false, scopeTypes: ['TENANT']}));
  const children = Array.from({length: 41}, (_, i) => ({id: 'resource-child-' + i, code: 'resource.child.' + String(i).padStart(3, '0'),
    name: 'Child' + String(i).padStart(3, '0'), type: 'ACTION', hasChildren: false, grantable: true, disabled: false, scopeTypes: ['TENANT']}));
  const grants = new Map([[applications[0].code, new Set(roots.slice(1, 502).map(row => row.code))], [applications[1].code, new Set([roots[1].code])]]);
  const nativeAssignedSnapshot = new Set(grants.get(applications[0].code));
  const reads = [], writes = [];
  let active = false, mode = 'all', failRead, failWrite = false, childHeld = false, releaseChild, delayedSearch = false;
  return {applications, roots, children, subtreeCodes, grants, reads, writes,
    enable() {active = true;}, mode(next) {mode = next;}, failRead(path) {failRead = path;}, failWrite() {failWrite = true;},
    delaySearch() {delayedSearch = true;}, holdChild() {childHeld = true;}, releaseChild() {releaseChild?.(); releaseChild = undefined; childHeld = false;},
    async handle(req, url, respond, expectedFailure) {
      if (!active) return false;
      const path = url.pathname, currentMode = mode;
      if (req.method === 'GET' && [base, base + '/schema', base + '/authorized'].includes(path)) {
        const read = {path, params: new URLSearchParams(url.searchParams), mode: currentMode, completed: false}; reads.push(read);
        if (path.endsWith('/schema')) {
          const result = structuredClone(schema);
          if (currentMode === 'viewer') result.buttons = [];
          if (currentMode === 'masked-app') {result.schema.properties.code['x-field-access'] = 'MASKED'; result.schema.properties.code.readOnly = true; delete result.schema['x-query'].fields.code;}
          read.completed = true; respond(result); return true;
        }
        if (path.endsWith('/authorized')) {
          const code = url.searchParams.get('applicationCode');
          if (!grants.has(code)) throw Error('Assigned codes lost their captured application');
          if (failRead === path) {failRead = undefined; expectedFailure(503); read.completed = true; respond({message: '已分配资源读取失败（测试）'}, 503); return true;}
          read.completed = true; respond([...grants.get(code)]); return true;
        }
        read.completed = true; respond(page(applications.map(row => ({...row, ...(currentMode === 'masked-app' ? {code: '***'} : {})})), url)); return true;
      }
      if (path === resourcesBase + '/children' || path === resourcesBase + '/assigned-tree' || path === resourcesBase + '/subtree-codes') {
        const payload = req.method === 'POST' ? await body(req) : undefined;
        const read = {path, params: new URLSearchParams(url.searchParams), payload, mode: currentMode, completed: false}; reads.push(read);
        if (currentMode === 'denied' || failRead === path) {
          failRead = undefined; expectedFailure(currentMode === 'denied' ? 403 : 503); read.completed = true;
          respond({message: '资源目录读取失败（测试）'}, currentMode === 'denied' ? 403 : 503); return true;
        }
        if (path.endsWith('/subtree-codes')) {
          if (url.searchParams.get('rootId') !== roots[0].id) throw Error('Subtree lost its actual resource ID');
          read.completed = true; respond(structuredClone(subtreeCodes)); return true;
        }
        if (url.searchParams.get('parentId') === roots[0].id && childHeld) {
          await new Promise(resolve => {releaseChild = resolve;});
        }
        if (url.searchParams.get('keyword') === 'Old' && delayedSearch) {delayedSearch = false; await wait(600);}
        if (path.endsWith('/children') && !url.searchParams.get('parentId') && !url.searchParams.get('keyword')
          && Number(url.searchParams.get('size')) === 20 && currentMode !== 'masked-resource'
          && directoryPages.has(Number(url.searchParams.get('page') ?? 0))) {
          read.completed = true; respond(structuredClone(directoryPages.get(Number(url.searchParams.get('page') ?? 0)))); return true;
        }
        let rows = url.searchParams.get('parentId') === roots[0].id ? children : roots;
        if (path.endsWith('/assigned-tree')) {
          if (!Array.isArray(payload) || payload.some(code => ![...roots, ...children].some(row => row.code === code))) throw Error('Assigned tree used presentation keys');
          const assigned = new Set(payload);
          const capturedPage = assignedPages.get(Number(url.searchParams.get('page') ?? 0));
          if (currentMode !== 'masked-resource' && !url.searchParams.get('parentId') && Number(url.searchParams.get('size')) === 20
            && capturedPage && assigned.size === nativeAssignedSnapshot.size && [...assigned].every(code => nativeAssignedSnapshot.has(code))) {
            read.completed = true; respond(structuredClone(capturedPage)); return true;
          }
          rows = rows.filter(row => assigned.has(row.code) || row.id === roots[0].id && children.some(child => assigned.has(child.code)))
            .map(row => ({...row, checked: assigned.has(row.code)}));
        } else if (url.searchParams.get('keyword')) {
          const keyword = url.searchParams.get('keyword'); rows = keyword === 'Old' ? [{...roots[2], name: 'OldResult'}] : rows.filter(row => row.name.includes(keyword));
        }
        if (currentMode === 'masked-resource') rows = rows.map(row => ({...row, id: '***', code: '***', grantable: '***', disabled: '***'}));
        read.completed = true; respond(page(rows, url)); return true;
      }
      if (req.method === 'POST' && [base + '/authorize', base + '/unauthorized'].includes(path)) {
        const data = await body(req);
        if (['viewer', 'masked-app', 'masked-resource', 'denied'].includes(currentMode) || !grants.has(data.applicationCode)
          || !Array.isArray(data.resourceCodes) || !data.resourceCodes.length
          || data.resourceCodes.some(code => ![...roots, ...children].some(row => row.code === code))) throw Error('Resource mutation lost real authorized codes');
        const write = {path, data, mode: currentMode, completed: false}; writes.push(write);
        const fail = failWrite; failWrite = false; await wait(500); write.completed = true;
        if (fail) {expectedFailure(503); respond({message: '应用资源保存失败（测试）'}, 503); return true;}
        for (const code of data.resourceCodes) {if (path.endsWith('/authorize')) grants.get(data.applicationCode).add(code); else grants.get(data.applicationCode).delete(code);}
        respond([]); return true;
      }
      return false;
    }};
}
