export const appWorkbenchRoute = {id: 'app-workbench-test', code: 'app.workbench.view', type: 'PAGE', routeKind: 'item',
  path: '/smoke/app-workbench', component: 'app/Workbench', label: '应用工作台测试', scopeTypes: ['TENANT'], requireOrgTenant: true};
const base = '/app/applications';

export function createAppWorkbenchFixture() {
  const applications = Array.from({length: 521}, (_, index) => ({id: 'app-' + index, name: 'Application' + index,
    description: 'actual metadata ' + index, status: 'DRAFT', mainPageName: 'Main' + index, attachedPageCount: index === 0 ? 520 : 0,
    updatedAt: '2026-10-10T00:00:00Z'}));
  const pages = new Map(applications.map((app, index) => [app.id, Array.from({length: index === 0 ? 521 : 1}, (_, page) => ({id: app.id + '-page-' + page,
    name: page === 0 ? app.mainPageName : 'Page' + page, kind: page === 0 ? 'MAIN' : 'ATTACHED', status: 'DRAFT', resourceRegistered: false}))]));
  let active = false, tenant, mode = 'all', fail;
  const requests = [], writes = [];
  return {requests, writes, applications, pages, enable(id) {active = true; tenant = id;}, mode(value) {mode = value;}, failWrite(path) {fail = path;},
    async handle(req, url, respond, expectedFailure) {
      if (!active || !(url.pathname === base || url.pathname.startsWith(base + '/'))) return false;
      if (req.headers['x-tenant-id'] !== tenant) throw Error('App workbench lost its actual tenant context');
      const path = url.pathname; requests.push({path, method: req.method, params: new URLSearchParams(url.searchParams)});
      if (path === base + '/permissions') {
        if (mode === 'denied') {expectedFailure(503); return respond({message: '应用权限读取失败（测试）'}, 503), true;}
        return respond({canCreateApplication: ['all', 'create'].includes(mode), canAddPage: ['all', 'page'].includes(mode)}), true;
      }
      if (req.method !== 'GET') {
        if (['readonly', 'denied'].includes(mode)) throw Error('Unauthorized app mutation');
        let raw = ''; for await (const chunk of req) raw += chunk;
        const data = JSON.parse(raw); if (!data.name.trim()) throw Error('App schema must reject blank names before the request');
        writes.push({path, data}); await new Promise(resolve => setTimeout(resolve, 350));
        if (fail === path) {fail = undefined; expectedFailure(503); return respond({message: '应用保存失败（测试）'}, 503), true;}
        if (path === base) {
          if (mode === 'page') throw Error('Add-page authority does not permit application creation');
          const app = {id: 'app-created', name: data.name, description: data.description, mainPageName: data.mainPageName,
            attachedPageCount: 0, status: 'DRAFT', updatedAt: '2026-10-10T00:00:00Z'};
          applications.unshift(app); pages.set(app.id, [{id: app.id + '-main', name: app.mainPageName, kind: 'MAIN', status: 'DRAFT', resourceRegistered: false}]);
          return respond({application: app, pages: pages.get(app.id)}), true;
        }
        const id = path.slice(base.length + 1).replace(/\/pages$/, '');
        if (!pages.has(id) || mode === 'create') throw Error('Add-page authority and an actual captured application are required');
        const page = {id: id + '-page-created', name: data.name, description: data.description, kind: 'ATTACHED', status: 'DRAFT', resourceRegistered: false};
        pages.get(id).push(page); applications.find(app=>app.id===id).attachedPageCount++; return respond(page), true;
      }
      if (path === base) {
        const page = Number(url.searchParams.get('page')), size = Number(url.searchParams.get('size'));
        if (size < 1 || size > 100) throw Error('Unbounded application list');
        const keyword = (url.searchParams.get('keyword') ?? '').toLowerCase();
        const rows = applications.filter(app=>app.name.toLowerCase().includes(keyword) || app.description?.toLowerCase().includes(keyword));
        return respond({content: rows.slice(page * size, (page + 1) * size), page: {number: page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}}), true;
      }
      const id = path.slice(base.length + 1); return respond({application: applications.find(app=>app.id===id), pages: pages.get(id)}), true;
    }};
}
