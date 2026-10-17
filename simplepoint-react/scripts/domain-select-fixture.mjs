import assert from 'node:assert/strict';

/** Context reads are independent of tenant-management permissions. No real account calls or writes. */
export function createDomainSelectFixture() {
  let enabled = false, fail = false, failAuthorization = false;
  const reads = [];
  const personalId = 'personal-01234567-89ab-cdef-0123-456789abcdef';
  const personalName = 'account-0123456789abcdef 的个人空间';
  const tenants = [{id: '__platform__', name: '平台工作台', type: 'PLATFORM'},
    ...Array.from({length: 241}, (_, index) => ({id: `workspace-${index}`, name: `Workspace${String(index).padStart(3, '0')}`, type: 'ORGANIZATION',
      logo: index === 0 ? '/svg.svg' : index === 239 ? '/common/current-directory/broken-logo' : null})),
    {id: personalId, name: personalName, type: 'PERSONAL'}];
  const roles = Array.from({length: 125}, (_, index) => ({id: `context-role-${index}`, name: `ContextRole${String(index).padStart(3, '0')}`, type: 'ROLE'}));
  return {reads, personalId, personalName, enable() {enabled = true;}, failNext() {fail = true;}, failAuthorization() {failAuthorization = true;}, async handle(req, url, respond, expectedFailure) {
    if (!enabled) return false;
    // Valid HTTP image location with an undecodable body exercises the real Avatar error fallback.
    if (url.pathname === '/common/current-directory/broken-logo') return respond({imageUnavailable: true}), true;
    if (url.pathname === '/common/tenants/authorization-context-id' && failAuthorization) {
      failAuthorization = false; expectedFailure(503); return respond({message: 'Authorization unavailable'}, 503), true;
    }
    if (url.pathname === '/common/tenants/current') return respond(tenants.map(row => ({tenantId: row.id, tenantName: row.name, tenantType: row.type, tenantLogo: row.logo}))), true;
    if (url.pathname === '/common/tenants/current-roles') return respond(roles.map(row => ({roleId: row.id, roleName: row.name}))), true;
    if (!url.pathname.startsWith('/common/current-directory/')) return false;
    assert.equal(req.method, 'GET');
    const record = {path: url.pathname, params: new URLSearchParams(url.searchParams)}; reads.push(record);
    if (fail && !url.pathname.endsWith('/selected')) {fail = false; expectedFailure(503); return respond({message: 'Directory unavailable'}, 503), true;}
    const rows = url.pathname.includes('/roles') ? roles : tenants;
    if (url.pathname.endsWith('/selected')) {
      const ids = url.searchParams.get('values').split(','); assert.ok(ids.length <= 50);
      return respond(rows.filter(row => ids.includes(row.id))), true;
    }
    const page = Number(url.searchParams.get('page')), size = Number(url.searchParams.get('size'));
    assert.ok(Number.isSafeInteger(page) && page >= 0 && size > 0 && size <= 20);
    const keyword = (url.searchParams.get('keyword') ?? '').toLowerCase();
    const filtered = rows.filter(row => row.name.toLowerCase().includes(keyword)
      || row.type === 'PERSONAL' && ('我的空间'.includes(keyword) || 'my workspace'.includes(keyword)));
    return respond({content: filtered.slice(page * size, (page + 1) * size),
      page: {number: page, size, totalElements: filtered.length, totalPages: Math.ceil(filtered.length / size)}}), true;
  }};
}
