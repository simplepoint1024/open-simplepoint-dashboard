import { http, HttpResponse } from 'msw';
import { pageFromUrl } from '../../../runtime';
import { createEntityStore } from '../../../runtime/store';

const base = '/common/field-scopes';

type FieldScopeEntry = {
  resource: string;
  field: string;
  access: 'EDITABLE' | 'VISIBLE' | 'MASKED' | 'HIDDEN';
};

type FieldScopeItem = {
  id: string;
  name: string;
  description?: string;
  entries: FieldScopeEntry[];
};

const store = createEntityStore<FieldScopeItem>([
  {
    id: 'fs-user-sensitive',
    name: '用户敏感字段脱敏',
    description: '手机号、邮箱等字段脱敏展示',
    entries: [
      { resource: 'User', field: 'phone', access: 'MASKED' },
      { resource: 'User', field: 'email', access: 'MASKED' },
      { resource: 'User', field: 'password', access: 'HIDDEN' },
    ],
  },
  {
    id: 'fs-readonly-audit',
    name: '审计字段只读',
    description: '创建人与更新时间可见但不可编辑',
    entries: [
      { resource: 'BaseEntityImpl', field: 'createdBy', access: 'VISIBLE' },
      { resource: 'BaseEntityImpl', field: 'updatedAt', access: 'VISIBLE' },
    ],
  },
]);

const schema = {
  buttons: [
    {
      path: '[default]',
      authority: 'field-scope.create',
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
      authority: 'field-scope.edit',
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
      authority: 'field-scope.delete',
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
    {
      path: '[default]',
      authority: 'field-scope.edit',
      color: 'blue',
      variant: 'outlined',
      icon: 'SafetyOutlined',
      argumentMaxSize: 1,
      sort: 3,
      type: 'primary',
      title: 'i18n:field-scopes.button.config.entries',
      danger: false,
      argumentMinSize: 1,
      key: 'config.entries',
    },
  ],
  schema: {
    $schema: 'http://json-schema.org/draft-07/schema#',
    type: 'object',
    properties: {
      name: {
        type: ['string', 'null'],
        title: 'i18n:field-scopes.title.name',
        description: 'i18n:field-scopes.description.name',
        minLength: 1,
        maxLength: 100,
        'x-order': 0,
        'x-ui': { 'x-list-visible': 'true' },
      },
      description: {
        type: ['string', 'null'],
        title: 'i18n:field-scopes.title.description',
        description: 'i18n:field-scopes.description.description',
        maxLength: 200,
        'x-order': 1,
        'x-ui': { 'x-list-visible': 'true' },
      },
      entries: {
        type: 'array',
        title: 'i18n:field-scopes.title.entries',
        description: 'i18n:field-scopes.description.entries',
        items: { type: 'object' },
        'x-order': 2,
        'x-ui': { 'x-list-visible': 'false' },
      },
    },
    required: ['name'],
  },
};

export default [
  http.get(`${base}/schema`, () => HttpResponse.json(schema)),
  http.get(base, ({ request }) => HttpResponse.json(pageFromUrl(new URL(request.url), store.all()))),
  http.post(base, async ({ request }) => {
    const payload = await request.json() as Partial<FieldScopeItem>;
    return HttpResponse.json(store.create({ entries: [], ...payload }));
  }),
  http.put(base, async ({ request }) => {
    const payload = await request.json() as Partial<FieldScopeItem>;
    return HttpResponse.json(store.update(payload));
  }),
  http.delete(base, ({ request }) => {
    const ids = (new URL(request.url).searchParams.get('ids') ?? '').split(',').filter(Boolean);
    return HttpResponse.json(store.remove(ids));
  }),
  http.put(`${base}/entries`, async ({ request }) => {
    const url = new URL(request.url);
    const fieldScopeId = url.searchParams.get('fieldScopeId');
    const entries = await request.json() as FieldScopeEntry[];
    const current = store.all().find((item) => item.id === fieldScopeId);
    if (!current) {
      return HttpResponse.json({ message: 'Field scope not found' }, { status: 404 });
    }
    return HttpResponse.json(store.update({ ...current, entries }));
  }),
];
