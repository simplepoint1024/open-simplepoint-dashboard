import {readFileSync} from 'node:fs';

const schema = JSON.parse(readFileSync(new URL('./common-main-schemas/Role.json', import.meta.url), 'utf8'));
const wait = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const body = async req => {let raw = ''; for await (const chunk of req) raw += chunk; return JSON.parse(raw);};
const page = (rows, url) => {
  const number = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
  if (!Number.isSafeInteger(number) || number < 0 || ![10, 20, 100].includes(size)) throw Error('Role lost bounded server paging');
  return {content: rows.slice(number * size, (number + 1) * size), page: {number, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}};
};

/** Actual generated Role schema and dedicated editor DTO contracts; all assignments remain in memory. */
export function createRoleLifecycleFixture() {
  const roles = [{id: 'role-owned-a', roleName: 'Role Alpha', authority: 'ROLE_ALPHA', priority: 1},
    {id: 'role-owned-b', roleName: 'Role Beta', authority: 'ROLE_BETA', priority: 2}];
  const root = {id: 'owned-root', code: 'owned.root', label: '真实资源目录', type: 'GROUP', grantable: false,
    hasChildren: true, hasNavigationChildren: true, dataPermission: {supported: false, policyId: null}, fieldPermission: {supported: false, policyId: null}};
  const children = Array.from({length: 521}, (_, i) => ({id: 'owned-resource-' + String(i).padStart(3, '0'),
    code: 'owned.resource.' + String(i).padStart(3, '0'), label: '真实资源' + String(i).padStart(3, '0'), type: 'PAGE', parentId: root.id,
    hasChildren: false, hasNavigationChildren: false, grantable: true, dataPermission: {supported: false, policyId: null}, fieldPermission: {supported: false, policyId: null}}));
  const grants = new Map([[roles[0].id, new Set(children.map(row => row.id))], [roles[1].id, new Set([children[0].id])]]);
  const versions = new Map(roles.map(role => [role.id, new Map(children.map(row => [row.id, 1]))]));
  const reads = [], writes = [];
  let enabled = false, mode = 'all', failSave = false, failDirectory = false, failPreview = false;
  let holdPreview = false, previewRelease, holdDirectory = false, directoryRelease;
  const rows = role => [root, ...children].map(row => ({...row, granted: grants.get(role).has(row.id), revision: 'v' + (versions.get(role).get(row.id) ?? 1)}));
  return {roles, root, children, grants, reads, writes, enable() {enabled = true;}, mode(next) {mode = next;},
    failSave() {failSave = true;}, failDirectory() {failDirectory = true;}, failPreview() {failPreview = true;},
    holdPreview() {holdPreview = true;}, releasePreview() {previewRelease?.(); previewRelease = undefined; holdPreview = false;},
    holdDirectory() {holdDirectory = true;}, releaseDirectory() {directoryRelease?.(); directoryRelease = undefined; holdDirectory = false;},
    async handle(req, url, respond, expectedFailure) {
      if (!enabled) return false;
      const path = url.pathname, currentMode = mode;
      if (req.method === 'GET' && [ '/common/roles', '/common/roles/schema'].includes(path)) {
        if (path.endsWith('/schema')) {const result = structuredClone(schema); if (currentMode === 'viewer') result.buttons = []; respond(result);}
        else respond(page(roles, url));
        return true;
      }
      const match = path.match(/^\/common\/role-resource-configuration\/roles\/(role-owned-[ab])\/(resources|resource-context|resource-changes|resource-subtree\/resources)$/);
      if (!match) return false;
      const role = match[1], kind = match[2];
      if (req.method === 'PUT') {
        const data = await body(req);
        if (currentMode === 'viewer' || kind !== 'resource-changes' || !Array.isArray(data.changes) || !data.changes.length || data.changes.length > 5000) throw Error('Unauthorized or empty role mutation');
        for (const change of data.changes) {
          const row = rows(role).find(row => row.id === change.resourceId);
          if (!row || row.code !== change.resourceCode || row.revision !== change.revision || typeof change.granted !== 'boolean') throw Error('Role mutation lost its captured baseline or real identity');
        }
        const write = {role, path, data, mode: currentMode, completed: false}; writes.push(write);
        const failure = failSave; failSave = false; await wait(500); write.completed = true;
        if (failure) {expectedFailure(503); respond({message: '角色资源保存失败（测试）'}, 503); return true;}
        for (const change of data.changes) {
          if (change.granted) grants.get(role).add(change.resourceId); else grants.get(role).delete(change.resourceId);
          versions.get(role).set(change.resourceId, versions.get(role).get(change.resourceId) + 1);
        }
        respond(null); return true;
      }
      const read = {role, path, kind, mode: currentMode, params: new URLSearchParams(url.searchParams), completed: false}; reads.push(read);
      if (currentMode === 'viewer') {expectedFailure(403); read.completed = true; respond({message: '无角色资源管理权限（测试）'}, 403); return true;}
      if (kind === 'resource-context') {
        const ids = await body(req); if (!Array.isArray(ids) || ids.length > 200) throw Error('Unbounded role context');
        read.completed = true; respond(rows(role).filter(row => ids.includes(row.id))); return true;
      }
      if (kind === 'resource-subtree/resources') {
        const id = url.searchParams.get('resourceId'); if (id !== root.id) throw Error('Directory lost its selected resource');
        if (holdDirectory) {holdDirectory = false; await new Promise(resolve => {directoryRelease = resolve;});}
        if (failDirectory) {failDirectory = false; expectedFailure(503); read.completed = true; respond({message: '角色目录读取失败（测试）'}, 503); return true;}
        read.completed = true; respond(rows(role)); return true;
      }
      const preview = url.searchParams.get('state') === 'granted' && url.searchParams.get('size') === '100';
      if (preview && holdPreview) {holdPreview = false; await new Promise(resolve => {previewRelease = resolve;});}
      if (preview && failPreview) {failPreview = false; expectedFailure(503); read.completed = true; respond({message: '角色预览读取失败（测试）'}, 503); return true;}
      let result = rows(role);
      if (url.searchParams.get('rootsOnly') === 'true') result = result.filter(row => !row.parentId);
      if (url.searchParams.has('parentId')) result = result.filter(row => row.parentId === url.searchParams.get('parentId'));
      if (url.searchParams.get('keyword')) result = result.filter(row => row.label.includes(url.searchParams.get('keyword')) || row.code.includes(url.searchParams.get('keyword')));
      if (url.searchParams.get('state') === 'granted') result = result.filter(row => row.granted);
      if (url.searchParams.get('state') === 'ungranted') result = result.filter(row => !row.granted);
      read.completed = true; respond(page(result, url)); return true;
    }};
}
