export function createOrganizationPickerFixture(member) {
  let enabled = false, mode = 'all';
  const reads = [], failures = new Map(), holds = new Map();
  const roots = Array.from({length: 121}, (_, index) => ({id: 'picker-root-' + index,
    name: 'Root' + String(index).padStart(3, '0'), code: 'R' + index, enabled: true, hasChildren: index < 25}));
  const children = Array.from({length: 521}, (_, index) => ({id: 'picker-child-' + index,
    name: 'Child' + String(index).padStart(3, '0'), code: 'C' + index, parentId: roots[0].id,
    enabled: index !== 3, hasChildren: false}));
  const keyOf = (kind, identity = '', page = 0) => JSON.stringify([kind, identity, page]);
  return {
    reads, roots, children,
    enable() {enabled = true; member.rows[0].orgId = children[520].id;},
    mode(value) {mode = value; member.readOnlyOrg(value === 'readonly' || value === 'denied');},
    failNext(kind, identity = '', page = 0, status = 503) {failures.set(keyOf(kind, identity, page), status);},
    hold(kind, identity = '', page = 0) {
      const key = keyOf(kind, identity, page); let release;
      const promise = new Promise(resolve => {release = resolve;}); holds.set(key, promise);
      return () => {holds.delete(key); release();};
    },
    async handle(req, url, respond, expectedFailure) {
      if (!enabled || url.pathname !== '/common/platform/organizations/options') return false;
      if (req.method !== 'GET') throw Error('Organization selector must only read its existing options endpoint');
      const params = new URLSearchParams(url.searchParams), page = Number(params.get('page')), size = Number(params.get('size'));
      if (!Number.isSafeInteger(page) || page < 0 || !Number.isSafeInteger(size) || size < 1 || size > 100)
        throw Error('Organization selector requested an unbounded or invalid page');
      const parent = params.get('parentId'), keyword = params.get('keyword'), ids = params.get('ids')?.split(',');
      const kind = ids ? 'lookup' : keyword ? 'search' : parent ? 'children' : 'root';
      const identity = ids?.join(',') ?? keyword ?? parent ?? '', key = keyOf(kind, identity, page);
      const read = {kind, params, mode, completed: false}; reads.push(read);
      const prefix = mode === 'scope-b' ? 'New scope ' : '';
      const siblings = parent === roots[0].id ? children : [{id: parent + '-child', name: 'Sibling of ' + parent,
        parentId: parent, enabled: true, hasChildren: false}];
      const source = ids ? [...roots, ...children] : keyword ? [...roots, ...children] : parent ? siblings : roots;
      const matches = source.filter(row => (!ids || ids.includes(row.id)) && (!keyword || row.name.toLowerCase().includes(keyword.toLowerCase()))
        && row.id !== params.get('excludeId'));
      const content = (mode === 'wrong-id' && kind === 'lookup' ? [children[0]] : matches.slice(page * size, (page + 1) * size))
        .map(row => ({...row, name: prefix + row.name}));
      // Protected Boolean fields are projection strings, not truthy eligibility flags.
      if (kind === 'children' && parent === roots[0].id && page === 0) {
        const masked = content.find(row => row.id === 'picker-child-4'); if (masked) {masked.enabled = '***'; masked.name = '***';}
      }
      const failure = mode === 'denied' && kind === 'lookup' ? 403 : failures.get(key);
      failures.delete(key); await holds.get(key); read.completed = true;
      if (failure) {expectedFailure(failure); return respond({message: '组织选项读取失败（测试）'}, failure), true;}
      return respond({content, number: page, size, hasNext: !ids && (page + 1) * size < matches.length}), true;
    },
  };
}
