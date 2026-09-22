import {compileLocalQuery} from '../libs/components/DataTable/localQuery.ts';

/** Isolated selection and assignment fixtures; never contacts production role/tenant records. */
export function createEntitySelectFixture() {
  let enabled = false;
  const requests = [], resourceRequests = [], resourceSaves = [], resourceBulks = [];
  let resourceConflict = false;
  const resourceRoot = {id: 'resource-root', code: 'resource.root', label: '测试资源目录', type: 'GROUP',
    icon: 'AppstoreOutlined', hasChildren: true, grantable: false, granted: false, revision: '1',
    dataPermission: {supported: false, policyId: null}, fieldPermission: {supported: false, policyId: null}};
  const resourceRows = [resourceRoot, ...Array.from({length: 240}, (_, i) => ({id: `resource-${String(i).padStart(3, '0')}`,
    code: `resource.${String(i).padStart(3, '0')}`, label: `测试资源 ${i}`, type: 'PAGE',
    icon: 'FileOutlined', parentId: resourceRoot.id, hasChildren: false,
    grantable: true, granted: i % 2 === 0, revision: '1',
    dataPermission: {supported: false, policyId: null}, fieldPermission: {supported: false, policyId: null}})),
    ...['ACTION', 'API'].map((type, index) => ({id: `resource-operation-${index}`,
      code: `resource.operation.${index}`, label: `测试操作 ${index}`, type,
      parentId: 'resource-001', grantable: true, granted: false, revision: '1',
      dataPermission: {supported: false, policyId: null}, fieldPermission: {supported: false, policyId: null}}))];
  const tenants = Array.from({length: 240}, (_, index) => ({id: `tenant-${String(index).padStart(3, '0')}`, name: `Tenant${String(index).padStart(3, '0')}`}));
  const descendants = (rootId, grantableOnly = true) => {
    const ids = new Set([rootId]);
    for (let changed = true; changed;) {
      changed = false;
      for (const row of resourceRows) if (ids.has(row.parentId) && !ids.has(row.id)) {ids.add(row.id); changed = true;}
    }
    return resourceRows.filter(row => ids.has(row.id) && (!grantableOnly || row.grantable));
  };
  const subtreeToken = rows => rows.map(row => `${row.id}:${row.granted}:${row.revision}`).join('|');
  return {requests, resourceRequests, resourceSaves, resourceBulks,
    resourceRows, conflictResources() {resourceConflict = true;}, enable() {enabled = true;}, async handle(req, url, respond, expectedFailure) {
    if (!enabled) return false;
    const path = url.pathname;
    const resourceMatch = path.match(/^\/common\/role-resource-configuration\/roles\/(Role-\d+)\/(resources|resource-changes|resource-subtree|resource-subtree\/resources)$/);
    if (resourceMatch) {
      if (resourceMatch[2] === 'resource-subtree/resources') {
        return respond(descendants(url.searchParams.get('resourceId'), false).map(row => ({...row,
          hasChildren: resourceRows.some(child => child.parentId === row.id),
          hasNavigationChildren: resourceRows.some(child => child.parentId === row.id && !['ACTION', 'API'].includes(child.type))}))), true;
      }
      if (resourceMatch[2] === 'resource-subtree') {
        if (req.method === 'GET') {
          const rows = descendants(url.searchParams.get('resourceId'));
          const granted = url.searchParams.get('granted') === 'true';
          return respond({resourceId: url.searchParams.get('resourceId'), granted, total: rows.length,
            changed: rows.filter(row => row.granted !== granted).length, revision: subtreeToken(rows)}), true;
        }
        const chunks = []; for await (const chunk of req) chunks.push(chunk);
        const payload = JSON.parse(Buffer.concat(chunks).toString());
        const rows = descendants(payload.resourceId);
        if (payload.revision !== subtreeToken(rows)) {expectedFailure(409); return respond({message: '目录资源已修改'}, 409), true;}
        resourceBulks.push(payload);
        for (const row of rows) if (row.granted !== payload.granted) {
          row.granted = payload.granted; row.revision = String(Number(row.revision) + 1);
        }
        return respond(null), true;
      }
      if (req.method === 'PUT') {
        const chunks = []; for await (const chunk of req) chunks.push(chunk);
        const payload = JSON.parse(Buffer.concat(chunks).toString());
        resourceSaves.push(payload);
        if (resourceConflict) {resourceConflict = false; expectedFailure(409); return respond({message: '资源授权已修改'}, 409), true;}
        for (const change of payload.changes) {
          const row = resourceRows.find(row => row.id === change.resourceId);
          if (!row || row.revision !== change.revision) throw Error('Stale resource revision');
          row.granted = change.granted; row.revision = String(Number(row.revision) + 1);
        }
        return respond(null), true;
      }
      resourceRequests.push(new URLSearchParams(url.searchParams));
      const page = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
      if (size > 100) throw Error('Unbounded resource page');
      const keyword = url.searchParams.get('keyword') ?? '', state = url.searchParams.get('state') ?? 'all';
      const rows = resourceRows.filter(row => (!url.searchParams.has('parentId') || row.parentId === url.searchParams.get('parentId'))
        && (url.searchParams.get('rootsOnly') !== 'true' || !row.parentId)
        && (url.searchParams.get('navigationOnly') !== 'true' || !['ACTION', 'API'].includes(row.type))
        && (row.code.includes(keyword) || row.label.includes(keyword))
        && (state === 'all' || row.granted === (state === 'granted')));
      return respond({content: rows.slice(page * size, (page + 1) * size).map(row => ({...row,
        hasChildren: resourceRows.some(child => child.parentId === row.id),
        hasNavigationChildren: resourceRows.some(child => child.parentId === row.id && !['ACTION', 'API'].includes(child.type))})),
        page: {number: page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}}), true;
    }
    if (path === '/common/platform/tenants') {
      requests.push(new URLSearchParams(url.searchParams));
      const page = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
      if (size > 20) throw Error('Entity selector must use bounded pages');
      const filters = Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort'].includes(key)));
      const query = compileLocalQuery({id: {type: 'string'}, name: {type: 'string'}}, {filters, sort: url.searchParams.getAll('sort')});
      const rows = tenants.filter(query.matches).sort(query.compare);
      return respond({content: rows.slice(page * size, (page + 1) * size), page: {number: page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}}), true;
    }
    return false;
  }};
}
