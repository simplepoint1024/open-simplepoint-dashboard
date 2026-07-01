import {http, HttpResponse} from 'msw';

const base = '/common/platform/tenants';

const tenants = [
  {
    id: 'tenant-demo',
    name: '演示租户',
    description: '默认演示租户，用于平台租户能力联调。',
    ownerId: 'admin',
    permissionVersion: 0,
  },
  {
    id: 'tenant-channel',
    name: '渠道伙伴租户',
    description: '模拟企业外部合作伙伴租户。',
    ownerId: 'operator',
    permissionVersion: 3,
  },
];

const users = [
  {id: 'admin', name: '平台管理员', email: 'admin@example.com', phoneNumber: '13800000001'},
  {id: 'operator', name: '渠道运营', email: 'operator@example.com', phoneNumber: '13800000002'},
  {id: 'alice', name: 'Alice', email: 'alice@example.com', phoneNumber: '13800000003'},
  {id: 'bob', name: 'Bob', email: 'bob@example.com', phoneNumber: '13800000004'},
];

let tenantPackages: Record<string, string[]> = {
  'tenant-demo': ['BASIC'],
  'tenant-channel': ['BASIC', 'PRO'],
};

let tenantUsers: Record<string, string[]> = {
  'tenant-demo': ['admin', 'alice'],
  'tenant-channel': ['operator', 'bob'],
};

const schema = {
  buttons: [
    {key: 'add', title: 'i18n:table.button.add', authority: 'tenants.create', sort: 0, argumentMinSize: 0, argumentMaxSize: 0},
    {key: 'edit', title: 'i18n:table.button.edit', authority: 'tenants.edit', sort: 1, argumentMinSize: 1, argumentMaxSize: 1},
    {key: 'delete', title: 'i18n:table.button.delete', authority: 'tenants.delete', sort: 2, argumentMinSize: 1, argumentMaxSize: 10},
    {key: 'config.package', title: 'i18n:table.button.config.package', authority: 'tenants.config.package', sort: 3, argumentMinSize: 1, argumentMaxSize: 1},
  ],
  schema: {
    type: 'object',
    properties: {
      name: {type: 'string', title: '租户名称'},
      description: {type: 'string', title: '租户描述'},
      ownerId: {type: 'string', title: '负责人', 'x-list-visible': false},
      permissionVersion: {type: 'integer', title: '权限版本', readOnly: true, 'x-list-visible': false},
    },
  },
};

const pageData = {
  content: tenants,
  page: {
    size: 10,
    number: 0,
    totalElements: tenants.length,
    totalPages: 1,
  },
};

const unique = (values: string[]) => Array.from(new Set(values));

export default [
  http.get(`${base}/schema`, () => HttpResponse.json(schema)),
  http.get(base, () => HttpResponse.json(pageData)),
  http.get(`${base}/owners/items`, () => HttpResponse.json({
    content: users,
    page: {
      size: users.length,
      number: 0,
      totalElements: users.length,
      totalPages: 1,
    },
  })),
  http.get(`${base}/authorized`, ({request}) => {
    const tenantId = new URL(request.url).searchParams.get('tenantId') ?? '';
    return HttpResponse.json(tenantPackages[tenantId] ?? []);
  }),
  http.post(`${base}/authorize`, async ({request}) => {
    const payload = await request.json() as {tenantId?: string | null; packageCodes?: string[]};
    const tenantId = payload.tenantId ?? '';
    tenantPackages[tenantId] = unique([...(tenantPackages[tenantId] ?? []), ...(payload.packageCodes ?? [])]);
    return HttpResponse.json((tenantPackages[tenantId] ?? []).map((packageCode) => ({tenantId, packageCode})));
  }),
  http.post(`${base}/unauthorized`, async ({request}) => {
    const payload = await request.json() as {tenantId?: string | null; packageCodes?: string[]};
    const tenantId = payload.tenantId ?? '';
    const removing = new Set(payload.packageCodes ?? []);
    tenantPackages[tenantId] = (tenantPackages[tenantId] ?? []).filter((code) => !removing.has(code));
    return HttpResponse.json(null);
  }),
  http.get(`${base}/users/items`, () => {
    const data = users;
    return HttpResponse.json({
      content: data,
      page: {
        size: data.length,
        number: 0,
        totalElements: data.length,
        totalPages: 1,
      },
    });
  }),
  http.get(`${base}/users/authorized`, ({request}) => {
    const tenantId = new URL(request.url).searchParams.get('tenantId') ?? '';
    const ownerId = tenants.find((tenant) => tenant.id === tenantId)?.ownerId;
    const content = ownerId ? unique([ownerId, ...(tenantUsers[tenantId] ?? [])]) : (tenantUsers[tenantId] ?? []);
    return HttpResponse.json(content);
  }),
  http.post(`${base}/users/authorize`, async ({request}) => {
    const payload = await request.json() as {tenantId?: string | null; userIds?: string[]};
    const tenantId = payload.tenantId ?? '';
    tenantUsers[tenantId] = unique([...(tenantUsers[tenantId] ?? []), ...(payload.userIds ?? [])]);
    return HttpResponse.json((payload.userIds ?? []).map((userId) => ({tenantId, userId})));
  }),
  http.post(`${base}/users/unauthorized`, async ({request}) => {
    const payload = await request.json() as {tenantId?: string | null; userIds?: string[]};
    const tenantId = payload.tenantId ?? '';
    const ownerId = tenants.find((tenant) => tenant.id === tenantId)?.ownerId;
    const removing = new Set((payload.userIds ?? []).filter((userId) => userId !== ownerId));
    tenantUsers[tenantId] = (tenantUsers[tenantId] ?? []).filter((userId) => !removing.has(userId));
    return HttpResponse.json(null);
  }),
];
