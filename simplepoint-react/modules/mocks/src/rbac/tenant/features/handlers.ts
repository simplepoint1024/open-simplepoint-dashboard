import {http, HttpResponse} from 'msw';

const base = '/common/platform/features';

const features = [
  {
    id: 'a1b2c3d4-0001-4000-8000-000000000001',
    createdAt: '2025-10-01T08:00:00.000000Z',
    updatedAt: '2025-10-01T08:00:00.000000Z',
    name: '首页工作台',
    code: 'DASHBOARD',
    description: '租户工作台首页与聚合卡片。',
    sort: 10,
    publicAccess: true,
  },
  {
    id: 'a1b2c3d4-0002-4000-8000-000000000002',
    createdAt: '2025-10-01T08:01:00.000000Z',
    updatedAt: '2025-10-01T08:01:00.000000Z',
    name: '访问控制',
    code: 'RBAC',
    description: '用户、角色、菜单与权限管理。',
    sort: 20,
    publicAccess: false,
  },
  {
    id: 'a1b2c3d4-0003-4000-8000-000000000003',
    createdAt: '2025-10-01T08:02:00.000000Z',
    updatedAt: '2025-10-01T08:02:00.000000Z',
    name: '国际化多语言',
    code: 'I18N',
    description: '多语言、时区、国家及地区管理。',
    sort: 30,
    publicAccess: false,
  },
  {
    id: 'a1b2c3d4-0004-4000-8000-000000000004',
    createdAt: '2025-10-01T08:03:00.000000Z',
    updatedAt: '2025-10-01T08:03:00.000000Z',
    name: '数据联邦',
    code: 'DNA',
    description: '跨数据源联邦查询与数据目录管理。',
    sort: 40,
    publicAccess: false,
  },
  {
    id: 'a1b2c3d4-0005-4000-8000-000000000005',
    createdAt: '2025-10-01T08:04:00.000000Z',
    updatedAt: '2025-10-01T08:04:00.000000Z',
    name: 'OIDC 授权',
    code: 'OIDC',
    description: 'OAuth2 / OIDC 客户端与授权服务器管理。',
    sort: 50,
    publicAccess: false,
  },
];

let featurePermissions: Record<string, string[]> = {
  DASHBOARD: ['dashboard.view'],
  RBAC: ['system.view', 'system.menu.view', 'system.role.view', 'system.user.view'],
  I18N: ['locale.view', 'locale.language.view', 'locale.message.view'],
  DNA: ['dna.view', 'dna.datasource.view', 'dna.catalog.view'],
  OIDC: ['oidc.view', 'oidc.clients.view'],
};

const schema = {
  buttons: [
    {
      path: '[default]',
      authority: 'features.create',
      variant: 'outlined',
      icon: 'PlusCircleOutlined',
      argumentMaxSize: 1,
      sort: 0,
      type: 'primary',
      title: 'i18n:table.button.create',
      danger: false,
      argumentMinSize: 0,
      key: 'add',
    },
    {
      path: '[default]',
      color: 'orange',
      authority: 'features.edit',
      variant: 'outlined',
      icon: 'EditOutlined',
      argumentMaxSize: 1,
      sort: 1,
      type: 'primary',
      title: 'i18n:table.button.edit',
      danger: false,
      argumentMinSize: 1,
      key: 'edit',
    },
    {
      path: '[default]',
      color: 'danger',
      authority: 'features.delete',
      variant: 'outlined',
      icon: 'MinusCircleOutlined',
      argumentMaxSize: 10,
      sort: 2,
      type: 'primary',
      title: 'i18n:table.button.delete',
      danger: true,
      argumentMinSize: 1,
      key: 'delete',
    },
    {
      path: '[default]',
      color: 'orange',
      authority: 'features.config.permission',
      variant: 'outlined',
      icon: 'SafetyOutlined',
      argumentMaxSize: 1,
      sort: 3,
      type: 'primary',
      title: 'i18n:features.button.config.permission',
      danger: false,
      argumentMinSize: 1,
      key: 'config.permission',
    },
  ],
  schema: {
    $schema: 'http://json-schema.org/draft-07/schema#',
    type: 'object',
    properties: {
      name: {
        type: ['string', 'null'],
        title: 'i18n:features.title.name',
        description: 'i18n:features.description.name',
        minLength: 1,
        maxLength: 128,
        'x-order': 0,
        'x-ui': {'x-list-visible': 'true'},
      },
      description: {
        type: ['string', 'null'],
        title: 'i18n:features.title.description',
        description: 'i18n:features.description.description',
        maxLength: 512,
        'x-order': 1,
        'x-ui': {'x-list-visible': 'true'},
      },
      code: {
        type: ['string', 'null'],
        title: 'i18n:features.title.code',
        description: 'i18n:features.description.code',
        minLength: 1,
        maxLength: 128,
        'x-order': 2,
        'x-ui': {'x-list-visible': 'true'},
      },
      sort: {
        type: 'integer',
        title: 'i18n:features.title.sort',
        description: 'i18n:features.description.sort',
        'x-order': 4,
        'x-ui': {'x-list-visible': 'true'},
      },
      publicAccess: {
        type: 'boolean',
        title: 'i18n:features.title.publicAccess',
        description: 'i18n:features.description.publicAccess',
        'x-order': 5,
        'x-ui': {'x-list-visible': 'true'},
      },
    },
  },
};

const pageData = {
  content: features,
  page: {
    size: 10,
    number: 0,
    totalElements: features.length,
    totalPages: 1,
  },
};

const itemsData = {
  content: features,
  page: {
    size: 2000,
    number: 0,
    totalElements: features.length,
    totalPages: 1,
  },
};

const unique = (values: string[]) => Array.from(new Set(values));

export default [
  http.get(`${base}/schema`, () => HttpResponse.json(schema)),
  http.get(`${base}/items/selected`, ({request}) => {
    const url = new URL(request.url);
    const codes = new Set((url.searchParams.get('codes') ?? '').split(',').filter(Boolean));
    return HttpResponse.json(features.filter((f) => codes.has(f.code)));
  }),
  http.get(`${base}/items`, () => HttpResponse.json(itemsData)),
  http.get(`${base}/authorized`, ({request}) => {
    const featureCode = new URL(request.url).searchParams.get('featureCode') ?? '';
    return HttpResponse.json(featurePermissions[featureCode] ?? []);
  }),
  http.get(base, () => HttpResponse.json(pageData)),
  http.post(`${base}/authorize`, async ({request}) => {
    const payload = await request.json() as {featureCode?: string | null; permissionAuthority?: string[]};
    const featureCode = payload.featureCode ?? '';
    featurePermissions[featureCode] = unique([...(featurePermissions[featureCode] ?? []), ...(payload.permissionAuthority ?? [])]);
    return HttpResponse.json((featurePermissions[featureCode] ?? []).map((authority) => ({featureCode, permissionAuthority: authority})));
  }),
  http.post(`${base}/unauthorized`, async ({request}) => {
    const payload = await request.json() as {featureCode?: string | null; permissionAuthority?: string[]};
    const featureCode = payload.featureCode ?? '';
    const removing = new Set(payload.permissionAuthority ?? []);
    featurePermissions[featureCode] = (featurePermissions[featureCode] ?? []).filter((authority) => !removing.has(authority));
    return HttpResponse.json(null);
  }),
  http.put(base, async ({request}) => {
    const body = await request.json() as typeof features[0];
    const idx = features.findIndex((f) => f.id === body.id);
    if (idx !== -1) features[idx] = {...features[idx], ...body, updatedAt: new Date().toISOString()};
    return HttpResponse.json(idx !== -1 ? features[idx] : body);
  }),
  http.post(base, async ({request}) => {
    const body = await request.json() as Omit<typeof features[0], 'id' | 'createdAt' | 'updatedAt'>;
    const newItem = {
      ...body,
      id: crypto.randomUUID(),
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    features.push(newItem as typeof features[0]);
    return HttpResponse.json(newItem, {status: 201});
  }),
  http.delete(base, ({request}) => {
    const ids = new Set((new URL(request.url).searchParams.get('ids') ?? '').split(',').filter(Boolean));
    const removed: string[] = [];
    ids.forEach((id) => {
      const idx = features.findIndex((f) => f.id === id);
      if (idx !== -1) { features.splice(idx, 1); removed.push(id); }
    });
    return HttpResponse.json(removed);
  }),
];
