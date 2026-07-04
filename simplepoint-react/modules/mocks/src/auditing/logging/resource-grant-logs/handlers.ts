import { http, HttpResponse } from 'msw';

const base = '/auditing/logging/resource-grant-logs';

const resourceGrantLogs = [
  {
    id: 'pclog01-0000-0000-0000-000000000001',
    createdAt: '2026-04-29T08:00:00.000000Z',
    updatedAt: '2026-04-29T08:00:00.000000Z',
    changedAt: '2026-04-29T08:00:00.000000Z',
    changeType: 'role',
    action: 'assign',
    subjectType: 'user',
    subjectId: 'alice-user-id',
    subjectName: 'Alice',
    targetType: 'role',
    targetCount: 1,
    operator: 'admin',
    tenantId: 'default',
    description: '为用户 Alice 分配角色 Developer',
  },
  {
    id: 'pclog01-0000-0000-0000-000000000002',
    createdAt: '2026-04-29T09:10:00.000000Z',
    updatedAt: '2026-04-29T09:10:00.000000Z',
    changedAt: '2026-04-29T09:10:00.000000Z',
    changeType: 'resource',
    action: 'revoke',
    subjectType: 'role',
    subjectId: 'developer-role-id',
    subjectName: 'Developer',
    targetType: 'resource',
    targetCount: 3,
    operator: 'admin',
    tenantId: 'default',
    description: '从角色 Developer 撤销 3 个资源',
  },
  {
    id: 'pclog01-0000-0000-0000-000000000003',
    createdAt: '2026-04-29T10:05:00.000000Z',
    updatedAt: '2026-04-29T10:05:00.000000Z',
    changedAt: '2026-04-29T10:05:00.000000Z',
    changeType: 'application',
    action: 'assign',
    subjectType: 'package',
    subjectId: 'enterprise-pkg-id',
    subjectName: '企业版套餐',
    targetType: 'resource',
    targetCount: 5,
    operator: 'admin',
    tenantId: 'tenant-a',
    description: '为套餐"企业版"添加 5 个资源',
  },
];

export default [
  http.get(`${base}/schema`, () => {
    return HttpResponse.json({
      buttons: [],
      schema: {
        type: 'object',
        properties: {
          changedAt: {
            type: 'string',
            format: 'date-time',
            title: '变更时间',
            'x-order': 1,
            'x-ui': { 'x-list-visible': 'true' },
          },
          changeType: {
            type: 'string',
            title: '变更类型',
            'x-order': 2,
            'x-ui': { 'x-list-visible': 'true' },
          },
          action: {
            type: 'string',
            title: '操作类型',
            'x-order': 3,
            'x-ui': { 'x-list-visible': 'true' },
          },
          subjectType: {
            type: 'string',
            title: '主体类型',
            'x-order': 4,
            'x-ui': { 'x-list-visible': 'true' },
          },
          subjectName: {
            type: 'string',
            title: '主体名称',
            'x-order': 5,
            'x-ui': { 'x-list-visible': 'true' },
          },
          targetType: {
            type: 'string',
            title: '变更目标',
            'x-order': 6,
            'x-ui': { 'x-list-visible': 'true' },
          },
          targetCount: {
            type: 'integer',
            title: '目标数量',
            'x-order': 7,
            'x-ui': { 'x-list-visible': 'true' },
          },
          operator: {
            type: 'string',
            title: '操作人',
            'x-order': 8,
            'x-ui': { 'x-list-visible': 'true' },
          },
          tenantId: {
            type: 'string',
            title: '租户ID',
            'x-order': 9,
            'x-ui': { 'x-list-visible': 'true' },
          },
          description: {
            type: 'string',
            title: '变更说明',
            'x-order': 10,
            'x-ui': { 'x-list-visible': 'true' },
          },
        },
      },
    });
  }),

  http.get(base, ({ request }) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? '0');
    const size = Number(url.searchParams.get('size') ?? '20');
    const start = page * size;
    const slice = resourceGrantLogs.slice(start, start + size);
    return HttpResponse.json({
      content: slice,
      page: {
        size,
        number: page,
        totalElements: resourceGrantLogs.length,
        totalPages: Math.ceil(resourceGrantLogs.length / size),
      },
    });
  }),
];
