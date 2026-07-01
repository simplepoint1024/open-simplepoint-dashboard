import {http, HttpResponse} from 'msw';

const base = '/common/platform/packages';

const packages = [
  {
    id: 'pkg-basic',
    name: '标准版',
    code: 'BASIC',
    description: '覆盖常用协同和基础管理能力。',
    price: 1999,
    durationDays: 365,
    sort: 10,
    enabled: true,
  },
  {
    id: 'pkg-pro',
    name: '专业版',
    code: 'PRO',
    description: '包含模块联邦应用接入和高级运营能力。',
    price: 4999,
    durationDays: 365,
    sort: 20,
    enabled: true,
  },
];

let packageApplications: Record<string, string[]> = {
  BASIC: ['COLLAB'],
  PRO: ['COLLAB', 'HR'],
};

const schema = {
  buttons: [
    {key: 'add', title: 'i18n:table.button.add', authority: 'packages.create', sort: 0, argumentMinSize: 0, argumentMaxSize: 0},
    {key: 'edit', title: 'i18n:table.button.edit', authority: 'packages.edit', sort: 1, argumentMinSize: 1, argumentMaxSize: 1},
    {key: 'delete', title: 'i18n:table.button.delete', authority: 'packages.delete', sort: 2, argumentMinSize: 1, argumentMaxSize: 10},
    {key: 'config.application', title: 'i18n:table.button.config.application', authority: 'packages.config.application', sort: 3, argumentMinSize: 1, argumentMaxSize: 1},
  ],
  schema: {
    type: 'object',
    properties: {
      name: {type: 'string', title: '套餐名称'},
      code: {type: 'string', title: '套餐编码'},
      description: {type: 'string', title: '套餐描述'},
      price: {type: 'number', title: '价格'},
      durationDays: {type: 'integer', title: '有效天数'},
      sort: {type: 'integer', title: '排序'},
      enabled: {type: 'boolean', title: '启用状态'},
    },
  },
};

const pageData = {
  content: packages,
  page: {
    size: 10,
    number: 0,
    totalElements: packages.length,
    totalPages: 1,
  },
};

const itemsData = {
  content: packages,
  page: {
    size: 2000,
    number: 0,
    totalElements: packages.length,
    totalPages: 1,
  },
};

const unique = (values: string[]) => Array.from(new Set(values));

export default [
  http.get(`${base}/schema`, () => HttpResponse.json(schema)),
  http.get(base, () => HttpResponse.json(pageData)),
  http.get(`${base}/items`, () => HttpResponse.json(itemsData)),
  http.get(`${base}/authorized`, ({request}) => {
    const packageCode = new URL(request.url).searchParams.get('packageCode') ?? '';
    return HttpResponse.json(packageApplications[packageCode] ?? []);
  }),
  http.post(`${base}/authorize`, async ({request}) => {
    const payload = await request.json() as {packageCode?: string | null; applicationCodes?: string[]};
    const packageCode = payload.packageCode ?? '';
    packageApplications[packageCode] = unique([...(packageApplications[packageCode] ?? []), ...(payload.applicationCodes ?? [])]);
    return HttpResponse.json((packageApplications[packageCode] ?? []).map((applicationCode) => ({packageCode, applicationCode})));
  }),
  http.post(`${base}/unauthorized`, async ({request}) => {
    const payload = await request.json() as {packageCode?: string | null; applicationCodes?: string[]};
    const packageCode = payload.packageCode ?? '';
    const removing = new Set(payload.applicationCodes ?? []);
    packageApplications[packageCode] = (packageApplications[packageCode] ?? []).filter((code) => !removing.has(code));
    return HttpResponse.json(null);
  }),
];
