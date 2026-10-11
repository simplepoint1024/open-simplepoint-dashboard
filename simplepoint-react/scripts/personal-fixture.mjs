import schemas from './personal-schemas.fixture.json' with {type: 'json'};
// Generated and checked by PersonalSchemaControllerTest using the production Jackson 3 converter.
// Isolated browser fixture only. Business rules are verified separately against Hibernate/SQL.
export const personalRoutes = [
  ['items', 'Items', '我的事项'], ['todos', 'Todos', '我的待办'], ['messages', 'Messages', '我的消息'],
].map(([key, component, label]) => ({id: 'my-' + key, code: 'common.view', type: 'PAGE', routeKind: 'item',
  path: '/my/' + key, component: 'common/personal/' + component, label, scopeTypes: ['PLATFORM', 'TENANT']}));
export const personalNavigation = [
  {id: 'home', code: 'dashboard.view', path: '/dashboard', component: 'common/Dashboard', label: '首页', routeKind: 'item', sort: 0, scopeTypes: ['PLATFORM']},
  {id: 'my', code: 'personal.view', path: '/my', label: '我的', routeKind: 'submenu', sort: 1, scopeTypes: ['PLATFORM', 'TENANT'], children: personalRoutes},
];
export function createPersonalFixture() {
  const now = new Date().toISOString();
  const records = {
    items: [{id: 'item-home', version: 0, title: '首页事项测试', description: '事项详情', category: '开发', priority: 2, status: 'TODO', pinned: false, archived: false, deletedAt: null, checklist: [], createdAt: now, updatedAt: now}],
    todos: [{id: 'todo-home', version: 0, title: '工作流待办测试', content: '请进入来源系统处理', priority: 2, status: 'PENDING', source: 'WORKFLOW', linkPath: '/my/items', createdAt: now}],
    messages: [{id: 'message-home', version: 0, title: '我的消息测试', content: '个人消息正文', priority: 1, source: 'WORKFLOW', readAt: null, archived: false, createdAt: now}],
  };
  const events = new Map();
  let count = 0;
  const state = {records, failNextRead: false, readFailures: [], failNextWrite: false, expectedFailures: [], async handle(req, url, respond) {
    const pathname = url.pathname;
    if (!pathname.startsWith('/common/personal/')) return false;
    const [kind, id, action] = pathname.slice('/common/personal/'.length).split('/');
    if (kind === 'overview') {
      const items = records.items.filter(r => !r.deletedAt && !r.archived && ['TODO', 'IN_PROGRESS'].includes(r.status));
      const todos = records.todos.filter(r => r.status === 'PENDING');
      const messages = records.messages.filter(r => !r.readAt && !r.archived);
      respond({activeItems: items.length, overdueItems: 0, pendingTodos: todos.length, unreadMessages: messages.length,
        items: items.slice(0, 5), todos: todos.slice(0, 5), messages: messages.slice(0, 5)}); return true;
    }
    if (id === 'schema' && schemas[kind]) {respond(schemas[kind]); return true;}
    const list = records[kind];
    if (!list) throw new Error('Unexpected personal endpoint: ' + pathname);
    if (req.method === 'GET' && !id && state.failNextRead) {
      state.failNextRead = false;
      state.readFailures.push(req.url);
      respond({code: 'INVALID_QUERY', message: '筛选值与字段类型不匹配'}, 400);
      return true;
    }
    let body;
    if (req.method !== 'GET') {
      if (state.failNextWrite) {
        state.failNextWrite = false;
        state.expectedFailures.push(req.url);
        respond({message: '事项保存冲突（测试），草稿未保存'}, 409);
        return true;
      }
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      body = JSON.parse(Buffer.concat(chunks).toString() || '{}');
    }
    if (kind === 'messages' && id === 'read-all') { list.forEach(r => {if (!r.archived) {r.readAt = now; r.version++;}}); respond({success: true}); return true; }
    let record = id ? list.find(r => r.id === decodeURIComponent(id)) : null;
    if (req.method === 'POST' && !id) {
      record = {id: 'created-' + (++count), version: 0, createdAt: now, updatedAt: now, status: kind === 'items' ? 'TODO' : 'PENDING',
        source: 'MANUAL', archived: false, deletedAt: null, checklist: [], ...body};
      record.version = 0; list.unshift(record); events.set(record.id, [{id: 'event-' + count, action: 'CREATED', createdAt: now}]);
      respond(record); return true;
    }
    if (req.method === 'PUT' && record) { Object.assign(record, body, {version: record.version + 1}); respond(record); return true; }
    if (action === 'actions') {
      if (body.action === 'COMMENT') events.set(record.id, [{id: 'event-' + (++count), action: 'COMMENT', detail: body.comment, createdAt: now}, ...(events.get(record.id) || [])]);
      if (body.action === 'START') record.status = 'IN_PROGRESS';
      if (body.action === 'COMPLETE') record.status = 'DONE';
      if (body.action === 'CANCEL') record.status = 'CANCELLED';
      if (body.action === 'REOPEN') record.status = kind === 'items' ? 'TODO' : 'PENDING';
      if (body.action === 'ARCHIVE') record.archived = true;
      if (body.action === 'UNARCHIVE') record.archived = false;
      if (body.action === 'DELETE') record.deletedAt = now;
      if (body.action === 'RESTORE') record.deletedAt = null;
      if (body.action === 'READ') record.readAt = now;
      if (body.action === 'UNREAD') record.readAt = null;
      record.version++; respond(record); return true;
    }
    if (id && action !== 'history') {respond(record); return true;}
    const view = url.searchParams.get('view'), status = url.searchParams.get('status'), keyword = url.searchParams.get('keyword') || '';
    let rows = action === 'history' ? events.get(id) || [] : list.filter(r => (!status || r.status === status) && r.title.includes(keyword));
    if (!action && kind === 'items') rows = rows.filter(r => view === 'TRASH' ? !!r.deletedAt : !r.deletedAt && (view === 'ARCHIVED' ? r.archived : !r.archived));
    if (!action && kind === 'messages') rows = rows.filter(r => !!r.archived === (url.searchParams.get('archived') === 'true') && (url.searchParams.get('unreadOnly') !== 'true' || !r.readAt));
    const filters = JSON.parse(url.searchParams.get('filters') || '{}');
    for (const [field, condition] of Object.entries(filters)) {
      if (condition.startsWith('{')) {
        const doc = JSON.parse(condition);
        const matches = (row, n) => {
          if (n.op === 'and') return n.children.every(c => matches(row, c));
          if (n.op === 'or') return n.children.some(c => matches(row, c));
          if (n.op === 'not') return !matches(row, n.children[0]);
          const value = row[n.field];
          if (n.op === 'like') return String(value ?? '').includes(n.value);
          if (n.op === 'equals') return value === n.value;
          if (n.op === 'in') return n.value.includes(value);
          if (n.op === 'is:null') return value == null;
          if (n.op === 'is:not:null') return value != null;
          throw new Error('Unsupported structured fixture op: ' + n.op);
        };
        rows = rows.filter(row => matches(row, doc.where));
      }
      else if (condition.startsWith('like:')) rows = rows.filter(row => String(row[field] ?? '').includes(condition.slice(5)));
      else if (condition.startsWith('in:')) rows = rows.filter(row => condition.slice(3).split(',').includes(String(row[field])));
      else throw new Error('Unsupported browser fixture filter: ' + condition);
    }
    const sorts = url.searchParams.getAll('sort').map(s => s.split(','));
    if (sorts.length) rows = [...rows].sort((a, b) => {
      for (const [field, direction] of sorts) {
        const diff = typeof a[field] === 'number' ? a[field] - b[field] : String(a[field] ?? '').localeCompare(String(b[field] ?? ''), 'zh-CN');
        if (diff) return diff * (direction === 'desc' ? -1 : 1);
      }
      return a.id.localeCompare(b.id);
    });
    const page = Number(url.searchParams.get('page') || 0), size = Number(url.searchParams.get('size') || 20);
    respond({content: rows.slice(page * size, (page + 1) * size), page: {number: page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}});
    return true;
  }};
  return state;
}
