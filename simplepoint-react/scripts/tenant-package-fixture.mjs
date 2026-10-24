import {readFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
import {fixturePage} from './query-fixture.mjs';
const base = '/common/tenants/current-packages';
const schema = JSON.parse(readFileSync(new URL('./tenant-package-contracts/schema.json', import.meta.url), 'utf8'));
const fields = Object.fromEntries(Object.entries(schema.schema.properties).map(([key, field]) => [key, {type: Array.isArray(field.type) ? field.type.find(type => type !== 'null') : field.type}]));
export const packageRoute = {code: 'workspace.packages.view', name: '我的套餐标准测试', label: '我的套餐标准测试',
  path: '/tenant/packages', component: 'host/TenantHome', type: 'PAGE', routeKind: 'item', scopeTypes: ['TENANT', 'PERSONAL'], grantable: true};
export function createTenantPackageFixture(workspace) {
  let active = false, fail = false, readDelay = 0;
  const rows = [
    {id: 'package-pending', packageCode: 'STANDARD', packageName: '团队办公', durationDays: 30, state: 'PENDING', acquisition: 'ASSIGNED',
      assignedAt: '2026-10-23T00:00:00Z', resourceCount: 45, allowedActions: ['resources', 'activate']},
    {id: 'package-legacy', packageCode: null, packageName: null, durationDays: null, state: 'ACTIVE', acquisition: 'LEGACY_ROLE',
      assignedAt: '2026-10-22T00:00:00Z', adoptedAt: '2026-10-22T00:00:00Z', resourceCount: 1, allowedActions: ['resources']},
  ];
  return {rows, writes: [], requests: [],
    enable() {active = true; workspace.enable();}, disable() {active = false; workspace.disable();},
    failNext() {fail = true;}, delayReads(ms) {readDelay = ms;},
    async handle(req, url, respond, expectedFailure) {
      if (!active || !url.pathname.startsWith(base)) return false;
      if (req.headers['x-tenant-id'] !== workspace.tenantId) throw Error('Package request crossed workspace');
      this.requests.push({path: url.pathname, query: new URLSearchParams(url.search), method: req.method});
      if (!workspace.profile.packageManageable) {
        expectedFailure(403); respond({messageKey: 'tenant.packages.error.OWNER_REQUIRED'}, 403); return true;
      }
      if (url.pathname === base + '/schema') {respond(schema); return true;}
      if (url.pathname === base) {respond(fixturePage(rows, fields, url)); return true;}
      const row = rows.find(row => url.pathname.startsWith(base + '/' + row.id + '/'));
      if (!row) throw Error('Unknown package instance request');
      if (url.pathname.endsWith('/activate') && req.method === 'POST') {
        this.writes.push({id: row.id}); await delay(350);
        if (fail) {fail = false; expectedFailure(503); respond({message: '激活失败（测试）'}, 503); return true;}
        row.state = 'ACTIVE'; row.activatedAt = '2026-10-23T02:00:00Z'; row.allowedActions = ['resources']; respond({instanceId: row.id}); return true;
      }
      if (!url.pathname.endsWith('/resources')) throw Error('Unexpected package endpoint');
      const page = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
      if (size > 100) throw Error('Unbounded package detail request');
      await delay(readDelay);
      const resources = Array.from({length: row.resourceCount}, (_, i) => ({id: `${row.id}-${i}`, resourceName: `办公资源${i}`,
        resourceCode: `office.resource.${i}`, durationDays: row.durationDays, currentState: row.id === 'package-legacy' ? 'UNLIMITED' : 'ACTIVE',
        activatedAt: row.activatedAt, creditedExpiresAt: row.activatedAt ? '2026-11-22T02:00:00Z' : null,
        currentStartsAt: '2026-10-01T00:00:00Z', currentExpiresAt: row.id === 'package-legacy' ? null : '2026-12-22T02:00:00Z'}));
      respond({content: resources.slice(page * size, (page + 1) * size), page: {number: page, size, totalElements: resources.length, totalPages: Math.ceil(resources.length / size)}}); return true;
    },
  };
}
