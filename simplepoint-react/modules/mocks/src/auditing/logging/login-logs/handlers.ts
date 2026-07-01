import { http, HttpResponse } from 'msw';

const base = '/auditing/logging/login-logs';

const loginLogs = [
  {
    id: 'log0001-0000-0000-0000-000000000001',
    createdAt: '2026-04-29T05:58:00.000000Z',
    updatedAt: '2026-04-29T05:58:00.000000Z',
    loginAt: '2026-04-29T05:58:00.000000Z',
    status: 'success',
    loginType: 'password',
    username: 'admin',
    displayName: '系统管理员',
    tenantId: 'default',
    clientIp: '192.168.1.100',
    requestUri: '/authorization/login',
    failureReason: null,
  },
  {
    id: 'log0001-0000-0000-0000-000000000002',
    createdAt: '2026-04-29T06:10:00.000000Z',
    updatedAt: '2026-04-29T06:10:00.000000Z',
    loginAt: '2026-04-29T06:10:00.000000Z',
    status: 'failure',
    loginType: 'password',
    username: 'alice',
    displayName: null,
    tenantId: 'default',
    clientIp: '10.0.0.55',
    requestUri: '/authorization/login',
    failureReason: '密码错误',
  },
  {
    id: 'log0001-0000-0000-0000-000000000003',
    createdAt: '2026-04-29T07:00:00.000000Z',
    updatedAt: '2026-04-29T07:00:00.000000Z',
    loginAt: '2026-04-29T07:00:00.000000Z',
    status: 'success',
    loginType: 'oidc',
    username: 'bob',
    displayName: 'Bob Smith',
    tenantId: 'tenant-a',
    clientIp: '172.16.0.8',
    requestUri: '/authorization/oauth2/callback',
    failureReason: null,
  },
  {
    id: 'log0001-0000-0000-0000-000000000004',
    createdAt: '2026-04-29T08:30:00.000000Z',
    updatedAt: '2026-04-29T08:30:00.000000Z',
    loginAt: '2026-04-29T08:30:00.000000Z',
    status: 'failure',
    loginType: 'password',
    username: 'charlie',
    displayName: null,
    tenantId: 'default',
    clientIp: '192.168.0.200',
    requestUri: '/authorization/login',
    failureReason: '账号已被锁定',
  },
  {
    id: 'log0001-0000-0000-0000-000000000005',
    createdAt: '2026-04-29T09:15:00.000000Z',
    updatedAt: '2026-04-29T09:15:00.000000Z',
    loginAt: '2026-04-29T09:15:00.000000Z',
    status: 'success',
    loginType: 'phone',
    username: '13800138000',
    displayName: '运营人员',
    tenantId: 'tenant-b',
    clientIp: '10.10.10.1',
    requestUri: '/authorization/login',
    failureReason: null,
  },
];

export default [
  http.get(`${base}/schema`, () => {
    return HttpResponse.json({
      buttons: [],
      schema: {
        type: 'object',
        properties: {
          loginAt: {
            type: 'string',
            format: 'date-time',
            title: '登录时间',
            'x-order': 1,
            'x-ui': { 'x-list-visible': 'true' },
          },
          status: {
            type: 'string',
            title: '登录状态',
            'x-order': 2,
            'x-ui': { 'x-list-visible': 'true' },
          },
          loginType: {
            type: 'string',
            title: '登录方式',
            'x-order': 3,
            'x-ui': { 'x-list-visible': 'true' },
          },
          username: {
            type: 'string',
            title: '登录账号',
            'x-order': 4,
            'x-ui': { 'x-list-visible': 'true' },
          },
          displayName: {
            type: 'string',
            title: '显示名称',
            'x-order': 5,
            'x-ui': { 'x-list-visible': 'true' },
          },
          tenantId: {
            type: 'string',
            title: '租户ID',
            'x-order': 6,
            'x-ui': { 'x-list-visible': 'true' },
          },
          clientIp: {
            type: 'string',
            title: '客户端IP',
            'x-order': 7,
            'x-ui': { 'x-list-visible': 'true' },
          },
          requestUri: {
            type: 'string',
            title: '请求地址',
            'x-order': 8,
            'x-ui': { 'x-list-visible': 'true' },
          },
          failureReason: {
            type: 'string',
            title: '失败原因',
            'x-order': 9,
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
    const slice = loginLogs.slice(start, start + size);
    return HttpResponse.json({
      content: slice,
      page: {
        size,
        number: page,
        totalElements: loginLogs.length,
        totalPages: Math.ceil(loginLogs.length / size),
      },
    });
  }),
];
