import {readFileSync} from 'node:fs';
import {compileLocalQuery} from '../libs/components/DataTable/localQuery.ts';

export const commonMainPages = [
  {name: 'Role', base: '/common/roles', component: 'system/Role', field: 'roleName'},
  {name: 'DataScope', base: '/common/data-scopes', component: 'system/DataScope', field: 'name'},
  {name: 'FieldScope', base: '/common/field-scopes', component: 'system/FieldScope', field: 'name'},
  {name: 'Dictionary', base: '/common/platform/dictionaries', component: 'platform/Dictionary', field: 'name'},
  {name: 'MicroModule', base: '/common/ops/microapps', component: 'ops/Microapp', field: 'displayName'},
  {name: 'ExternalIdentityProvider', base: '/common/oidc/external-providers', component: 'system/IdentityProvider', field: 'displayName'},
  {name: 'PlatformAccount', base: '/common/platform/accounts', component: 'platform/Account', field: 'name'},
  {name: 'PlatformAccountAudit', base: '/common/platform/accounts/audit', component: 'platform/AccountAudit', field: 'action'},
  {name: 'Client', base: '/common/oidc/clients', component: 'system/OAuthClient', field: 'clientName'},
  {name: 'SystemNotification', base: '/common/system/notifications', component: 'system/Notification', field: 'title'},
  {name: 'StorageProvider', base: '/common/platform/object-storage/provider-configs', component: 'platform/ObjectStorage', field: 'name', schemaFile: 'provider-configs', route: '/smoke/system/object-storage/providers'},
  {name: 'StorageObject', base: '/common/platform/object-storage/objects', component: 'platform/ObjectStorage', field: 'originalFileName', schemaFile: 'objects'},
  {name: 'StorageQuota', base: '/common/platform/object-storage/quotas', component: 'platform/ObjectStorage', field: 'tenantName', schemaFile: 'quotas', route: '/smoke/platform/storage-quotas'},
];
export const commonMainRoutes = commonMainPages.map(page => ({id: `common-main-${page.name}`, code: 'common.view',
  type: 'PAGE', routeKind: 'item', path: page.route ?? `/smoke/common-main/${page.name}`, component: `common/${page.component}`,
  label: `契约测试 ${page.name}`, scopeTypes: ['PLATFORM']}));

/** Schemas exported by real HTTP+SQL regression; all writes remain isolated from business services. */
export function createCommonMainFixture() {
  const specs = [...commonMainPages, {name: 'DictionaryItem', base: '/common/platform/dictionary-items', field: 'name'}];
  const datasets = specs.map(spec => {
    const schema = JSON.parse(readFileSync(new URL(spec.schemaFile ? `./storage-schemas/${spec.schemaFile}.json` : `./common-main-schemas/${spec.name}.json`, import.meta.url), 'utf8'));
    const fields = Object.fromEntries(Object.entries(schema.schema['x-query'].fields).map(([name, field]) => {
      const java = field.javaType;
      return [name, {type: ['Boolean', 'boolean'].includes(java) ? 'boolean' : ['Integer', 'Long', 'BigInteger', 'int', 'long'].includes(java) ? 'integer'
        : java === 'Instant' ? 'date-time' : 'string'}];
    }));
    const rows = Array.from({length: 240}, (_, i) => {
      const padded = String(i).padStart(3, '0');
      const row = Object.fromEntries(Object.entries(fields).map(([key, field]) => [key,
        field.type === 'boolean' ? i % 2 === 0 : field.type === 'integer' ? i
          : field.type === 'date-time' ? '2026-09-19T00:00:00Z' : `${key}-${padded}`]));
      Object.assign(row, {id: `${spec.name}-${padded}`, entries: [], customDeptIds: [], enabled: true});
      if (spec.name === 'DataScope') row.type = 'ALL';
      if (spec.name === 'PlatformAccount') Object.assign(row, {roles: ['AUDITOR'], superAdmin: false});
      if (spec.name === 'SystemNotification') Object.assign(row, {category: 'SYSTEM', priority: 'NORMAL', audienceType: 'ALL', status: i % 2 === 0 ? 'DRAFT' : 'PUBLISHED'});
      if (spec.name === 'DictionaryItem') row.dictionaryCode = i < 120 ? 'code-000' : 'code-001';
      if (spec.name === 'ExternalIdentityProvider') Object.assign(row, {protocol: 'OIDC', preset: 'CUSTOM', matchStrategy: 'NONE', clientSecretConfigured: true});
      return row;
    });
    return {...spec, schema, fields, rows};
  });
  let enabled = false, failedBase;
  const requests = [];
  return {
    datasets, requests, enable() {enabled = true;}, failNext(base) {failedBase = base;},
    async handle(req, url, respond, expectedFailure) {
      if (!enabled) return false;
      if (url.pathname === '/common/platform/dictionaries/options') {
        const options = {
          'notification.category': ['ANNOUNCEMENT', 'SYSTEM', 'MAINTENANCE', 'SECURITY'],
          'notification.priority': ['LOW', 'NORMAL', 'HIGH', 'URGENT'],
          'notification.audience-type': ['ALL', 'TENANT', 'USER'],
          'notification.status': ['DRAFT', 'PUBLISHED', 'REVOKED'],
        }[url.searchParams.get('dictionaryCode')];
        if (options) return respond(options.map(value => ({value, label: value === 'DRAFT' ? '草稿' : value}))), true;
      }
      if (url.pathname === '/common/platform/accounts/capabilities') return respond({permissions: [
        'platform.accounts.view', 'platform.accounts.create', 'platform.accounts.edit', 'platform.identity.manage', 'platform.audit.view',
      ], twoFactorEnabled: false}), true;
      const dataset = datasets.find(spec => url.pathname === spec.base || url.pathname === `${spec.base}/schema`);
      if (!dataset) return false;
      if (req.method !== 'GET') throw Error('Common main query smoke must not write domain records');
      if (url.pathname.endsWith('/schema')) return respond(dataset.schema), true;
      requests.push({name: dataset.name, params: new URLSearchParams(url.searchParams)});
      if (failedBase === dataset.base) {
        failedBase = undefined; expectedFailure(400); return respond({code: 'INVALID_QUERY', message: '测试查询条件已失效'}, 400), true;
      }
      const page = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
      if (page < 0 || size < 1 || size > dataset.schema.schema['x-query'].maxPageSize) throw Error('Common main query page budget exceeded');
      const filters = Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort', 'parentDictionaryCode'].includes(key)));
      const query = compileLocalQuery(dataset.fields, {filters, sort: url.searchParams.getAll('sort')});
      const parent = url.searchParams.get('parentDictionaryCode');
      if (dataset.name === 'DictionaryItem' && !parent) throw Error('Dictionary item drawer lost its parent scope');
      const rows = dataset.rows.filter(row => (!parent || row.dictionaryCode === parent) && query.matches(row)).sort(query.compare);
      return respond({content: rows.slice(page * size, (page + 1) * size),
        page: {number: page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}}), true;
    },
  };
}
