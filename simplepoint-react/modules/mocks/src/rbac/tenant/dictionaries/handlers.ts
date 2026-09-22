import {http, HttpResponse} from 'msw';
import dictionarySchema from '../../../../../../scripts/common-main-schemas/Dictionary.json';
import dictionaryItemSchema from '../../../../../../scripts/common-main-schemas/DictionaryItem.json';
import {compileLocalQuery, type LocalFields} from '../../../../../../libs/components/DataTable/localQuery';

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
  {
    id: 'dict-tenant-type',
    name: '租户类型',
    code: 'tenant.type',
    description: '用于定义租户工作空间的使用形态',
    sort: 35,
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
  {
    id: 'dict-item-tenant-type-personal',
    dictionaryCode: 'tenant.type',
    name: 'PERSONAL',
    i18nKey: 'tenants.type.PERSONAL',
    value: 'PERSONAL',
    description: '个人租户工作空间',
    sort: 20,
    enabled: true,
  },
  {
    id: 'dict-item-tenant-type-organization',
    dictionaryCode: 'tenant.type',
    name: 'ORGANIZATION',
    i18nKey: 'tenants.type.ORGANIZATION',
    value: 'ORGANIZATION',
    description: '组织租户工作空间',
    sort: 10,
    enabled: true,
  },
];

const nextId = (prefix: string) => `${prefix}-${Math.random().toString(36).slice(2, 10)}`;

const sortBySort = <T extends {sort?: number | null; name?: string; value?: string}>(content: T[]) =>
  [...content].sort((a, b) => {
    const sortA = typeof a.sort === 'number' ? a.sort : Number.MAX_SAFE_INTEGER;
    const sortB = typeof b.sort === 'number' ? b.sort : Number.MAX_SAFE_INTEGER;
    if (sortA !== sortB) return sortA - sortB;
    const nameA = a.name ?? a.value ?? '';
    const nameB = b.name ?? b.value ?? '';
    return nameA.localeCompare(nameB);
  });

const queryPage = (rows: Array<DictionaryRecord | DictionaryItemRecord>, request: Request, items = false) => {
  const url = new URL(request.url);
  const page = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
  const parent = url.searchParams.get('parentDictionaryCode');
  try {
    if (!Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1 || size > 200) throw Error('分页范围超限');
    if (parent !== null && (!parent.trim() || parent.length > 128)) throw Error('字典编码无效');
    const schema = items ? dictionaryItemSchema : dictionarySchema;
    const fields: LocalFields = Object.fromEntries(Object.entries(schema.schema['x-query'].fields).map(([name, field]) => [name,
      {type: field.javaType === 'Boolean' ? 'boolean' : field.javaType === 'Integer' ? 'integer' : field.javaType === 'Instant' ? 'date-time' : 'string'}]));
    const query = compileLocalQuery(fields, {
      filters: Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort', 'parentDictionaryCode'].includes(key))),
      sort: url.searchParams.getAll('sort'),
    });
    const matches = rows.filter(row => (!items || parent === null || (row as DictionaryItemRecord).dictionaryCode === parent)
      && query.matches(row)).sort(query.compare);
    return HttpResponse.json({content: matches.slice(page * size, (page + 1) * size),
      page: {number: page, size, totalElements: matches.length, totalPages: Math.ceil(matches.length / size)}});
  } catch {
    return HttpResponse.json({code: 'INVALID_QUERY', message: '无效的字典查询条件'}, {status: 400});
  }
};

export default [
  http.get(`${dictionariesBase}/schema`, () => HttpResponse.json(dictionarySchema)),
  http.get(`${dictionariesBase}`, ({request}) => queryPage(dictionaries, request)),
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
  http.get(`${itemsBase}`, ({request}) => queryPage(dictionaryItems, request, true)),
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
