import {http, HttpResponse} from 'msw';

const base = '/common/platform/organizations';
const organizationTypes = new Set(['group', 'unit', 'department', 'team']);

type OrganizationRecord = {
  id: string;
  tenantId: string;
  name: string;
  code: string;
  type: string;
  parentId?: string | null;
  description?: string | null;
  sort?: number | null;
  enabled?: boolean;
};

let organizations: OrganizationRecord[] = [
  {
    id: 'org-demo-root',
    tenantId: 'tenant-demo',
    name: '演示租户总部',
    code: 'DEMO-HQ',
    type: 'group',
    description: '演示租户的总部组织',
    sort: 10,
    enabled: true,
  },
  {
    id: 'org-demo-sales',
    tenantId: 'tenant-demo',
    name: '销售中心',
    code: 'DEMO-SALES',
    type: 'department',
    parentId: 'org-demo-root',
    description: '负责演示租户的销售协同',
    sort: 20,
    enabled: true,
  },
  {
    id: 'org-channel-root',
    tenantId: 'tenant-channel',
    name: '渠道伙伴总部',
    code: 'CHANNEL-HQ',
    type: 'unit',
    description: '渠道伙伴租户的总部组织',
    sort: 10,
    enabled: true,
  },
  {
    id: 'org-channel-service',
    tenantId: 'tenant-channel',
    name: '客户成功部',
    code: 'CHANNEL-CS',
    type: 'team',
    parentId: 'org-channel-root',
    description: '负责伙伴租户售后与客户成功',
    sort: 20,
    enabled: true,
  },
];

const nextId = () => `org-${Math.random().toString(36).slice(2, 10)}`;

const trimToNull = (value: unknown) => {
  if (typeof value !== 'string') return value == null ? null : String(value);
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
};

const requireTenantId = (request: Request) => request.headers.get('X-Tenant-Id')?.trim() ?? '';

const sortOrganizations = (records: OrganizationRecord[]) =>
  [...records].sort((a, b) => {
    const sortA = typeof a.sort === 'number' ? a.sort : Number.MAX_SAFE_INTEGER;
    const sortB = typeof b.sort === 'number' ? b.sort : Number.MAX_SAFE_INTEGER;
    if (sortA !== sortB) return sortA - sortB;
    return `${a.name}:${a.code}`.localeCompare(`${b.name}:${b.code}`);
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

const buildParentOptions = (tenantId: string) =>
  sortOrganizations(organizations.filter((organization) => organization.tenantId === tenantId))
    .map((organization) => ({
      const: organization.id,
      title: `${organization.name} (${organization.code})`,
    }));

const buildSchema = (tenantId: string) => ({
  buttons: [
    {key: 'add', title: 'i18n:table.button.add', authority: 'organizations.create', sort: 0, argumentMinSize: 0, argumentMaxSize: 0},
    {key: 'edit', title: 'i18n:table.button.edit', authority: 'organizations.edit', sort: 1, argumentMinSize: 1, argumentMaxSize: 1},
    {key: 'delete', title: 'i18n:table.button.delete', authority: 'organizations.delete', sort: 2, argumentMinSize: 1, argumentMaxSize: 10},
  ],
  schema: {
    type: 'object',
    properties: {
      name: {type: 'string', title: '组织名称', 'x-ui': {'x-list-visible': 'true'}},
      code: {type: 'string', title: '组织编码', 'x-ui': {'x-list-visible': 'true'}},
      type: {
        type: 'string',
        title: 'i18n:organizations.title.type',
        'x-ui': {'x-list-visible': 'true', widget: 'select', dictCode: 'organization.type'},
      },
      parentId: {
        type: 'string',
        title: '上级组织',
        oneOf: buildParentOptions(tenantId),
        'x-ui': {'x-list-visible': 'true', widget: 'select'},
      },
      description: {type: 'string', title: '描述', 'x-ui': {'x-list-visible': 'true'}},
      sort: {type: 'integer', title: '排序', 'x-ui': {'x-list-visible': 'true'}},
      enabled: {type: 'boolean', title: '启用', 'x-ui': {'x-list-visible': 'true'}},
    },
  },
});

const validateParent = (tenantId: string, parentId?: string | null, currentId?: string) => {
  const normalizedParentId = trimToNull(parentId) as string | null;
  if (!normalizedParentId) return null;
  if (currentId && normalizedParentId === currentId) {
    throw new Error('上级组织不能选择自己');
  }
  const parent = organizations.find(
    (organization) => organization.id === normalizedParentId && organization.tenantId === tenantId
  );
  if (!parent) {
    throw new Error('上级组织不存在');
  }
  let cursor = trimToNull(parent.parentId) as string | null;
  const visited = new Set<string>([parent.id]);
  while (cursor) {
    if (currentId && cursor === currentId) {
      throw new Error('不能将组织移动到自己的下级组织下');
    }
    if (visited.has(cursor)) {
      throw new Error('组织层级数据异常，请检查上级组织配置');
    }
    visited.add(cursor);
    const current = organizations.find(
      (organization) => organization.id === cursor && organization.tenantId === tenantId
    );
    if (!current) {
      throw new Error('组织层级数据异常，请检查上级组织配置');
    }
    cursor = trimToNull(current.parentId) as string | null;
  }
  return normalizedParentId;
};

const validateCode = (tenantId: string, code: string, currentId?: string) => {
  const normalizedCode = (trimToNull(code) as string | null) ?? '';
  if (!normalizedCode) {
    throw new Error('组织编码不能为空');
  }
  const duplicated = organizations.some(
    (organization) =>
      organization.tenantId === tenantId &&
      organization.code === normalizedCode &&
      organization.id !== currentId
  );
  if (duplicated) {
    throw new Error('当前租户下组织编码已存在');
  }
  return normalizedCode;
};

const validateType = (value: unknown) => {
  const normalizedType = (trimToNull(value) as string | null) ?? '';
  if (!normalizedType) {
    throw new Error('组织类型不能为空');
  }
  const exists = organizationTypes.has(normalizedType);
  if (!exists) {
    throw new Error('组织类型无效');
  }
  return normalizedType;
};

const validateDelete = (tenantId: string, ids: string[]) => {
  const removing = new Set(ids);
  const childIds = organizations
    .filter((organization) => organization.tenantId === tenantId && organization.parentId && removing.has(organization.parentId))
    .map((organization) => organization.id)
    .filter((id) => !removing.has(id));
  if (childIds.length > 0) {
    throw new Error('请先删除子组织机构后再删除当前组织');
  }
};

export default [
  http.get(`${base}/schema`, ({request}) => {
    const tenantId = requireTenantId(request);
    return HttpResponse.json(buildSchema(tenantId));
  }),
  http.get(base, ({request}) => {
    const tenantId = requireTenantId(request);
    if (!tenantId) {
      return HttpResponse.json(buildPage([]));
    }
    return HttpResponse.json(buildPage(sortOrganizations(
      organizations.filter((organization) => organization.tenantId === tenantId)
    )));
  }),
  http.post(base, async ({request}) => {
    const tenantId = requireTenantId(request);
    if (!tenantId) {
      return new HttpResponse('请先选择租户', {status: 400});
    }
    try {
      const payload = await request.json() as Record<string, any>;
      const organization: OrganizationRecord = {
        id: payload.id ?? nextId(),
        tenantId,
        name: (trimToNull(payload.name) as string | null) ?? '',
        code: validateCode(tenantId, payload.code),
        type: validateType(payload.type),
        parentId: validateParent(tenantId, payload.parentId),
        description: trimToNull(payload.description) as string | null,
        sort: typeof payload.sort === 'number' ? payload.sort : null,
        enabled: payload.enabled ?? true,
      };
      if (!organization.name) {
        return new HttpResponse('组织名称不能为空', {status: 400});
      }
      organizations = [organization, ...organizations];
      return HttpResponse.json(organization);
    } catch (error) {
      return new HttpResponse(String((error as Error)?.message ?? error), {status: 400});
    }
  }),
  http.put(base, async ({request}) => {
    const tenantId = requireTenantId(request);
    if (!tenantId) {
      return new HttpResponse('请先选择租户', {status: 400});
    }
    try {
      const payload = await request.json() as Record<string, any>;
      const current = organizations.find(
        (organization) => organization.id === payload.id && organization.tenantId === tenantId
      );
      if (!current) {
        return new HttpResponse('组织机构不存在', {status: 404});
      }
      const nextOrganization: OrganizationRecord = {
        ...current,
        ...payload,
        tenantId,
        name: (trimToNull(payload.name) as string | null) ?? current.name,
        code: validateCode(tenantId, payload.code ?? current.code, current.id),
        type: validateType(payload.type ?? current.type),
        parentId: validateParent(tenantId, payload.parentId ?? current.parentId, current.id),
        description: trimToNull(payload.description ?? current.description) as string | null,
        sort: typeof payload.sort === 'number' ? payload.sort : current.sort ?? null,
        enabled: payload.enabled ?? current.enabled ?? true,
      };
      if (!nextOrganization.name) {
        return new HttpResponse('组织名称不能为空', {status: 400});
      }
      organizations = organizations.map((organization) =>
        organization.id === payload.id && organization.tenantId === tenantId ? nextOrganization : organization
      );
      return HttpResponse.json(nextOrganization);
    } catch (error) {
      return new HttpResponse(String((error as Error)?.message ?? error), {status: 400});
    }
  }),
  http.delete(base, ({request}) => {
    const tenantId = requireTenantId(request);
    if (!tenantId) {
      return new HttpResponse('请先选择租户', {status: 400});
    }
    try {
      const ids = (new URL(request.url).searchParams.get('ids') ?? '').split(',').filter(Boolean);
      validateDelete(tenantId, ids);
      organizations = organizations.filter(
        (organization) => organization.tenantId !== tenantId || !ids.includes(organization.id)
      );
      return HttpResponse.json(ids);
    } catch (error) {
      return new HttpResponse(String((error as Error)?.message ?? error), {status: 400});
    }
  }),
];
