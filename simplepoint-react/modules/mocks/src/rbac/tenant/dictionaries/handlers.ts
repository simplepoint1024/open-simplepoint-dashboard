import {http, HttpResponse} from 'msw';

const dictionariesBase = '/common/platform/dictionaries';
const itemsBase = '/common/platform/dictionary-items';

type DictionaryRecord = {
  id: string;
  name: string;
  code: string;
  description?: string;
  sort?: number | null;
  enabled?: boolean;
};

type DictionaryItemRecord = {
  id: string;
  dictionaryCode: string;
  name: string;
  i18nKey?: string;
  value: string;
  description?: string;
  sort?: number | null;
  enabled?: boolean;
};

let dictionaries: DictionaryRecord[] = [
  {
    id: 'dict-resource-type',
    name: '资源类型',
    code: 'resource.type',
    description: '用于定义统一资源模型中的类型枚举',
    sort: 10,
    enabled: true,
  },
  {
    id: 'dict-organization-type',
    name: '组织类型',
    code: 'organization.type',
    description: '用于定义组织机构层级类型',
    sort: 20,
    enabled: true,
  },
];

let dictionaryItems: DictionaryItemRecord[] = [
  {
    id: 'dict-item-resource-group',
    dictionaryCode: 'resource.type',
    name: '分组',
    i18nKey: 'resources.type.GROUP',
    value: 'GROUP',
    description: '用于组织资源树的一级或逻辑分组',
    sort: 10,
    enabled: true,
  },
  {
    id: 'dict-item-resource-module',
    dictionaryCode: 'resource.type',
    name: '模块',
    i18nKey: 'resources.type.MODULE',
    value: 'MODULE',
    description: '用于表示插件或业务模块',
    sort: 20,
    enabled: true,
  },
  {
    id: 'dict-item-resource-page',
    dictionaryCode: 'resource.type',
    name: '页面',
    i18nKey: 'resources.type.PAGE',
    value: 'PAGE',
    description: '用于展示前端路由页面',
    sort: 30,
    enabled: true,
  },
  {
    id: 'dict-item-resource-feature',
    dictionaryCode: 'resource.type',
    name: '功能',
    i18nKey: 'resources.type.FEATURE',
    value: 'FEATURE',
    description: '用于表示页面内的功能能力',
    sort: 40,
    enabled: true,
  },
  {
    id: 'dict-item-resource-action',
    dictionaryCode: 'resource.type',
    name: '操作',
    i18nKey: 'resources.type.ACTION',
    value: 'ACTION',
    description: '用于表示按钮、命令等可授权操作',
    sort: 50,
    enabled: true,
  },
  {
    id: 'dict-item-resource-api',
    dictionaryCode: 'resource.type',
    name: '接口',
    i18nKey: 'resources.type.API',
    value: 'API',
    description: '用于表示后端接口能力',
    sort: 60,
    enabled: true,
  },
  {
    id: 'dict-item-organization-type-group',
    dictionaryCode: 'organization.type',
    name: '集团',
    i18nKey: 'organizations.type.group',
    value: 'group',
    description: '用于表示集团级组织',
    sort: 10,
    enabled: true,
  },
  {
    id: 'dict-item-organization-type-unit',
    dictionaryCode: 'organization.type',
    name: '单位',
    i18nKey: 'organizations.type.unit',
    value: 'unit',
    description: '用于表示单位级组织',
    sort: 20,
    enabled: true,
  },
  {
    id: 'dict-item-organization-type-department',
    dictionaryCode: 'organization.type',
    name: '部门',
    i18nKey: 'organizations.type.department',
    value: 'department',
    description: '用于表示部门级组织',
    sort: 30,
    enabled: true,
  },
  {
    id: 'dict-item-organization-type-team',
    dictionaryCode: 'organization.type',
    name: '小组',
    i18nKey: 'organizations.type.team',
    value: 'team',
    description: '用于表示小组级组织',
    sort: 40,
    enabled: true,
  },
];

const dictionarySchema = {
  buttons: [
    {key: 'add', title: 'i18n:table.button.add', authority: 'dictionaries.create', sort: 0, argumentMinSize: 0, argumentMaxSize: 0},
    {key: 'edit', title: 'i18n:table.button.edit', authority: 'dictionaries.edit', sort: 1, argumentMinSize: 1, argumentMaxSize: 1},
    {key: 'delete', title: 'i18n:table.button.delete', authority: 'dictionaries.delete', sort: 2, argumentMinSize: 1, argumentMaxSize: 10},
    {key: 'config.item', title: 'i18n:dictionaries.button.config.item', authority: 'dictionaries.config.item', sort: 3, argumentMinSize: 1, argumentMaxSize: 1},
  ],
  schema: {
    type: 'object',
    properties: {
      name: {type: 'string', title: 'i18n:dictionaries.title.name'},
      code: {type: 'string', title: 'i18n:dictionaries.title.code'},
      description: {type: 'string', title: 'i18n:dictionaries.title.description'},
      sort: {type: 'integer', title: 'i18n:dictionaries.title.sort'},
      enabled: {type: 'boolean', title: 'i18n:dictionaries.title.enabled'},
    },
  },
};

const dictionaryItemSchema = {
  buttons: [
    {key: 'add', title: 'i18n:table.button.add', authority: 'dictionaries.create', sort: 0, argumentMinSize: 0, argumentMaxSize: 0},
    {key: 'edit', title: 'i18n:table.button.edit', authority: 'dictionaries.edit', sort: 1, argumentMinSize: 1, argumentMaxSize: 1},
    {key: 'delete', title: 'i18n:table.button.delete', authority: 'dictionaries.delete', sort: 2, argumentMinSize: 1, argumentMaxSize: 10},
  ],
  schema: {
    type: 'object',
    properties: {
      dictionaryCode: {type: 'string', title: 'i18n:dictionaries.title.dictionaryCode', 'x-ui': {'x-list-visible': 'false'}},
      name: {type: 'string', title: 'i18n:dictionaries.title.itemName'},
      i18nKey: {type: 'string', title: 'i18n:dictionaries.title.i18nKey'},
      value: {type: 'string', title: 'i18n:dictionaries.title.value'},
      description: {type: 'string', title: 'i18n:dictionaries.title.description'},
      sort: {type: 'integer', title: 'i18n:dictionaries.title.sort'},
      enabled: {type: 'boolean', title: 'i18n:dictionaries.title.enabled'},
    },
  },
};

const nextId = (prefix: string) => `${prefix}-${Math.random().toString(36).slice(2, 10)}`;

const parseFilterValue = (raw: string | null) => {
  if (!raw) return '';
  const separatorIndex = raw.indexOf(':');
  return separatorIndex === -1 ? raw : raw.slice(separatorIndex + 1);
};

const sortBySort = <T extends {sort?: number | null; name?: string; value?: string}>(content: T[]) =>
  [...content].sort((a, b) => {
    const sortA = typeof a.sort === 'number' ? a.sort : Number.MAX_SAFE_INTEGER;
    const sortB = typeof b.sort === 'number' ? b.sort : Number.MAX_SAFE_INTEGER;
    if (sortA !== sortB) return sortA - sortB;
    const nameA = a.name ?? a.value ?? '';
    const nameB = b.name ?? b.value ?? '';
    return nameA.localeCompare(nameB);
  });

const buildPage = <T,>(content: T[]) => ({
  content,
  page: {
    size: 10,
    number: 0,
    totalElements: content.length,
    totalPages: 1,
  },
});

export default [
  http.get(`${dictionariesBase}/schema`, () => HttpResponse.json(dictionarySchema)),
  http.get(`${dictionariesBase}`, () => HttpResponse.json(buildPage(sortBySort(dictionaries)))),
  http.post(`${dictionariesBase}`, async ({request}) => {
    const payload = await request.json() as Partial<DictionaryRecord>;
    const dictionary: DictionaryRecord = {
      ...payload,
      id: payload.id ?? nextId('dict'),
      name: payload.name ?? '',
      code: payload.code ?? '',
      enabled: payload.enabled ?? true,
    };
    dictionaries = [dictionary, ...dictionaries];
    return HttpResponse.json(dictionary);
  }),
  http.put(`${dictionariesBase}`, async ({request}) => {
    const payload = await request.json() as Partial<DictionaryRecord>;
    const current = dictionaries.find((dictionary) => dictionary.id === payload.id);
    const nextDictionary: DictionaryRecord = {
      ...current,
      ...payload,
      id: payload.id ?? current?.id ?? nextId('dict'),
      name: payload.name ?? current?.name ?? '',
      code: payload.code ?? current?.code ?? '',
      enabled: payload.enabled ?? current?.enabled ?? true,
    };
    dictionaries = dictionaries.map((dictionary) => dictionary.id === payload.id ? nextDictionary : dictionary);
    if (current && current.code !== nextDictionary.code) {
      dictionaryItems = dictionaryItems.map((item) => item.dictionaryCode === current.code ? {...item, dictionaryCode: nextDictionary.code} : item);
    }
    return HttpResponse.json(nextDictionary);
  }),
  http.delete(`${dictionariesBase}`, ({request}) => {
    const ids = (new URL(request.url).searchParams.get('ids') ?? '').split(',').filter(Boolean);
    const removingCodes = new Set(
      dictionaries
        .filter((dictionary) => ids.includes(dictionary.id))
        .map((dictionary) => dictionary.code)
    );
    dictionaries = dictionaries.filter((dictionary) => !ids.includes(dictionary.id));
    dictionaryItems = dictionaryItems.filter((item) => !removingCodes.has(item.dictionaryCode));
    return HttpResponse.json(ids);
  }),
  http.get(`${dictionariesBase}/options`, ({request}) => {
    const dictionaryCode = new URL(request.url).searchParams.get('dictionaryCode') ?? '';
    const options = sortBySort(
      dictionaryItems.filter((item) => item.dictionaryCode === dictionaryCode && item.enabled !== false)
    ).map((item) => ({
      value: item.value,
      label: item.i18nKey ? `i18n:${item.i18nKey}` : item.name,
    }));
    return HttpResponse.json(options);
  }),
  http.get(`${itemsBase}/schema`, () => HttpResponse.json(dictionaryItemSchema)),
  http.get(`${itemsBase}`, ({request}) => {
    const url = new URL(request.url);
    const dictionaryCode = parseFilterValue(url.searchParams.get('dictionaryCode'));
    const content = sortBySort(
      dictionaryItems.filter((item) => !dictionaryCode || item.dictionaryCode === dictionaryCode)
    );
    return HttpResponse.json(buildPage(content));
  }),
  http.post(`${itemsBase}`, async ({request}) => {
    const payload = await request.json() as Partial<DictionaryItemRecord>;
    const item: DictionaryItemRecord = {
      ...payload,
      id: payload.id ?? nextId('dict-item'),
      dictionaryCode: payload.dictionaryCode ?? '',
      name: payload.name ?? '',
      value: payload.value ?? '',
      enabled: payload.enabled ?? true,
    };
    dictionaryItems = [item, ...dictionaryItems];
    return HttpResponse.json(item);
  }),
  http.put(`${itemsBase}`, async ({request}) => {
    const payload = await request.json() as Partial<DictionaryItemRecord>;
    const current = dictionaryItems.find((item) => item.id === payload.id);
    const nextItem: DictionaryItemRecord = {
      ...current,
      ...payload,
      id: payload.id ?? current?.id ?? nextId('dict-item'),
      dictionaryCode: payload.dictionaryCode ?? current?.dictionaryCode ?? '',
      name: payload.name ?? current?.name ?? '',
      value: payload.value ?? current?.value ?? '',
      enabled: payload.enabled ?? current?.enabled ?? true,
    };
    dictionaryItems = dictionaryItems.map((item) => item.id === payload.id ? nextItem : item);
    return HttpResponse.json(nextItem);
  }),
  http.delete(`${itemsBase}`, ({request}) => {
    const ids = (new URL(request.url).searchParams.get('ids') ?? '').split(',').filter(Boolean);
    dictionaryItems = dictionaryItems.filter((item) => !ids.includes(item.id));
    return HttpResponse.json(ids);
  }),
];
