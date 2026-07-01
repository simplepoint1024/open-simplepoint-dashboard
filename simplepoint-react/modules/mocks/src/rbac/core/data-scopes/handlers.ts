import { http, HttpResponse } from 'msw';
import { pageFromUrl } from '../../../runtime';
import { createEntityStore } from '../../../runtime/store';

const base = '/common/data-scopes';

type DataScopeItem = {
  id: string;
  name: string;
  type: 'ALL' | 'CUSTOM' | 'DEPT' | 'DEPT_AND_BELOW' | 'SELF';
  customDeptIds: string[];
  description?: string;
};

const store = createEntityStore<DataScopeItem>([
  {
    id: 'ds-all',
    name: '全部数据',
    type: 'ALL',
    customDeptIds: [],
    description: '可访问当前租户内所有数据',
  },
  {
    id: 'ds-dept-below',
    name: '本部门及下级',
    type: 'DEPT_AND_BELOW',
    customDeptIds: [],
    description: '适合部门负责人使用',
  },
  {
    id: 'ds-custom-product',
    name: '产品与研发部门',
    type: 'CUSTOM',
    customDeptIds: ['org-product', 'org-rd'],
    description: '仅授权产品、研发相关组织数据',
  },
  {
    id: 'ds-self',
    name: '仅本人',
    type: 'SELF',
    customDeptIds: [],
    description: '只能访问本人创建或归属的数据',
  },
]);

const schema = {
  buttons: [
    {
      path: '[default]',
      authority: 'data-scope.create',
      variant: 'outlined',
      icon: 'PlusCircleOutlined',
      argumentMaxSize: 1,
      sort: 0,
      type: 'primary',
      title: 'i18n:table.button.add',
      danger: false,
      argumentMinSize: 0,
      key: 'add',
    },
    {
      path: '[default]',
      authority: 'data-scope.edit',
      color: 'orange',
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
      authority: 'data-scope.delete',
      color: 'danger',
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
  ],
  schema: {
    $schema: 'http://json-schema.org/draft-07/schema#',
    type: 'object',
    properties: {
      name: {
        type: ['string', 'null'],
        title: 'i18n:data-scopes.title.name',
        description: 'i18n:data-scopes.description.name',
        minLength: 1,
        maxLength: 100,
        'x-order': 0,
        'x-ui': { 'x-list-visible': 'true' },
      },
      type: {
        type: 'string',
        title: 'i18n:data-scopes.title.type',
        description: 'i18n:data-scopes.description.type',
        enum: ['ALL', 'DEPT_AND_BELOW', 'DEPT', 'CUSTOM', 'SELF'],
        enumNames: [
          'i18n:data-scopes.title.type.ALL',
          'i18n:data-scopes.title.type.DEPT_AND_BELOW',
          'i18n:data-scopes.title.type.DEPT',
          'i18n:data-scopes.title.type.CUSTOM',
          'i18n:data-scopes.title.type.SELF',
        ],
        'x-order': 1,
        'x-ui': { 'x-list-visible': 'true' },
      },
      customDeptIds: {
        type: 'array',
        title: 'i18n:data-scopes.title.customDeptIds',
        description: 'i18n:data-scopes.description.customDeptIds',
        items: { type: 'string' },
        uniqueItems: true,
        'x-order': 2,
        'x-ui': { 'x-list-visible': 'false' },
      },
      description: {
        type: ['string', 'null'],
        title: 'i18n:data-scopes.title.description',
        description: 'i18n:data-scopes.description.description',
        maxLength: 200,
        'x-order': 3,
        'x-ui': { 'x-list-visible': 'true' },
      },
    },
    required: ['name', 'type'],
  },
};

export default [
  http.get(`${base}/schema`, () => HttpResponse.json(schema)),
  http.get(base, ({ request }) => HttpResponse.json(pageFromUrl(new URL(request.url), store.all()))),
  http.post(base, async ({ request }) => {
    const payload = await request.json() as Partial<DataScopeItem>;
    return HttpResponse.json(store.create({
      ...payload,
      customDeptIds: payload.type === 'CUSTOM' ? payload.customDeptIds ?? [] : [],
    }));
  }),
  http.put(base, async ({ request }) => {
    const payload = await request.json() as Partial<DataScopeItem>;
    return HttpResponse.json(store.update({
      ...payload,
      customDeptIds: payload.type === 'CUSTOM' ? payload.customDeptIds ?? [] : [],
    }));
  }),
  http.delete(base, ({ request }) => {
    const ids = (new URL(request.url).searchParams.get('ids') ?? '').split(',').filter(Boolean);
    return HttpResponse.json(store.remove(ids));
  }),
];
