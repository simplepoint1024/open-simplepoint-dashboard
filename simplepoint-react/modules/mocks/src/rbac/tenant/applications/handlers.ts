import {http, HttpResponse} from 'msw';

const base = '/common/platform/applications';

const applications = [
  {
    id: 'app-collaboration',
    name: '协同门户',
    code: 'COLLAB',
    description: '统一工作台与协同入口。',
    homepage: '/workspace',
    sort: 10,
    enabled: true,
  },
  {
    id: 'app-hr',
    name: '人事服务',
    code: 'HR',
    description: '员工档案与审批流程。',
    homepage: '/hr',
    sort: 20,
    enabled: true,
  },
];

let applicationFeatures: Record<string, string[]> = {
  COLLAB: ['DASHBOARD'],
  HR: ['APPROVAL'],
};

const schema = {
  buttons: [
    {key: 'add', title: 'i18n:table.button.add', authority: 'applications.create', sort: 0, argumentMinSize: 0, argumentMaxSize: 0},
    {key: 'edit', title: 'i18n:table.button.edit', authority: 'applications.edit', sort: 1, argumentMinSize: 1, argumentMaxSize: 1},
    {key: 'delete', title: 'i18n:table.button.delete', authority: 'applications.delete', sort: 2, argumentMinSize: 1, argumentMaxSize: 10},
    {key: 'config.feature', title: 'i18n:table.button.config.feature', authority: 'applications.config.feature', sort: 3, argumentMinSize: 1, argumentMaxSize: 1},
  ],
  schema: {
    type: 'object',
    properties: {
      name: {type: 'string', title: '应用名称'},
      code: {type: 'string', title: '应用编码'},
      description: {type: 'string', title: '应用描述'},
      homepage: {type: 'string', title: '首页地址'},
      sort: {type: 'integer', title: '排序'},
      enabled: {type: 'boolean', title: '启用状态'},
    },
  },
};

const pageData = {
  content: applications,
  page: {
    size: 10,
    number: 0,
    totalElements: applications.length,
    totalPages: 1,
  },
};

const itemsData = {
  content: applications,
  page: {
    size: 2000,
    number: 0,
    totalElements: applications.length,
    totalPages: 1,
  },
};

const unique = (values: string[]) => Array.from(new Set(values));

export default [
  http.get(`${base}/schema`, () => HttpResponse.json(schema)),
  http.get(base, () => HttpResponse.json(pageData)),
  http.get(`${base}/items`, () => HttpResponse.json(itemsData)),
  http.get(`${base}/authorized`, ({request}) => {
    const applicationCode = new URL(request.url).searchParams.get('applicationCode') ?? '';
    return HttpResponse.json(applicationFeatures[applicationCode] ?? []);
  }),
  http.post(`${base}/authorize`, async ({request}) => {
    const payload = await request.json() as {applicationCode?: string | null; featureCodes?: string[]};
    const applicationCode = payload.applicationCode ?? '';
    applicationFeatures[applicationCode] = unique([...(applicationFeatures[applicationCode] ?? []), ...(payload.featureCodes ?? [])]);
    return HttpResponse.json((applicationFeatures[applicationCode] ?? []).map((featureCode) => ({applicationCode, featureCode})));
  }),
  http.post(`${base}/unauthorized`, async ({request}) => {
    const payload = await request.json() as {applicationCode?: string | null; featureCodes?: string[]};
    const applicationCode = payload.applicationCode ?? '';
    const removing = new Set(payload.featureCodes ?? []);
    applicationFeatures[applicationCode] = (applicationFeatures[applicationCode] ?? []).filter((code) => !removing.has(code));
    return HttpResponse.json(null);
  }),
];
