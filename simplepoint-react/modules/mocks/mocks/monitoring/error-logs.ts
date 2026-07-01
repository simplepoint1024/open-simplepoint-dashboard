import { http, HttpResponse } from 'msw';

const base = '/auditing/logging/error-logs';

const errorLogs = [
  {
    id: 'elog001-0000-0000-0000-000000000001',
    createdAt: '2026-04-29T03:00:00.000000Z',
    updatedAt: '2026-04-29T03:00:00.000000Z',
    occurredAt: '2026-04-29T03:00:00.000000Z',
    level: 'ERROR',
    sourceService: 'simplepoint-service-common',
    loggerName: 'org.simplepoint.plugin.rbac.core.service.impl.UsersServiceImpl',
    message: 'User not found for id: unknown-id',
    exceptionType: 'java.util.NoSuchElementException',
    exceptionMessage: 'No value present',
    tenantId: 'default',
    userId: 'admin',
    requestUri: '/users',
    clientIp: '127.0.0.1',
  },
  {
    id: 'elog001-0000-0000-0000-000000000002',
    createdAt: '2026-04-29T04:15:00.000000Z',
    updatedAt: '2026-04-29T04:15:00.000000Z',
    occurredAt: '2026-04-29T04:15:00.000000Z',
    level: 'WARN',
    sourceService: 'simplepoint-service-host',
    loggerName: 'org.simplepoint.plugin.auditing.rate.limit.gateway.RateLimitFilter',
    message: 'Rate limit exceeded for service: dna',
    exceptionType: null,
    exceptionMessage: null,
    tenantId: 'tenant-a',
    userId: null,
    requestUri: '/dna/logging/query',
    clientIp: '10.0.0.1',
  },
  {
    id: 'elog001-0000-0000-0000-000000000003',
    createdAt: '2026-04-29T05:30:00.000000Z',
    updatedAt: '2026-04-29T05:30:00.000000Z',
    occurredAt: '2026-04-29T05:30:00.000000Z',
    level: 'ERROR',
    sourceService: 'simplepoint-service-authorization',
    loggerName: 'org.simplepoint.plugin.oidc.service.impl.OidcClientServiceImpl',
    message: 'Failed to load OIDC client config',
    exceptionType: 'org.springframework.dao.DataAccessException',
    exceptionMessage: 'Connection refused',
    tenantId: null,
    userId: null,
    requestUri: '/oauth2/token',
    clientIp: '192.168.1.50',
  },
  {
    id: 'elog001-0000-0000-0000-000000000004',
    createdAt: '2026-04-29T06:45:00.000000Z',
    updatedAt: '2026-04-29T06:45:00.000000Z',
    occurredAt: '2026-04-29T06:45:00.000000Z',
    level: 'WARN',
    sourceService: 'simplepoint-service-common',
    loggerName: 'org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl',
    message: 'Slow query detected (>500ms) on table simpoint_rbac_users',
    exceptionType: null,
    exceptionMessage: null,
    tenantId: 'default',
    userId: null,
    requestUri: null,
    clientIp: null,
  },
  {
    id: 'elog001-0000-0000-0000-000000000005',
    createdAt: '2026-04-29T08:00:00.000000Z',
    updatedAt: '2026-04-29T08:00:00.000000Z',
    occurredAt: '2026-04-29T08:00:00.000000Z',
    level: 'ERROR',
    sourceService: 'simplepoint-service-common',
    loggerName: 'org.simplepoint.plugin.dna.federation.service.impl.FederationJdbcDriverServiceImpl',
    message: 'Federation datasource connection failed',
    exceptionType: 'java.sql.SQLException',
    exceptionMessage: 'Communications link failure',
    tenantId: 'tenant-b',
    userId: 'dna-admin',
    requestUri: '/dna/federation/query',
    clientIp: '172.16.0.5',
  },
];

export default [
  http.get(`${base}/schema`, () => {
    return HttpResponse.json({
      buttons: [],
      schema: {
        type: 'object',
        properties: {
          occurredAt: {
            type: 'string',
            format: 'date-time',
            title: '发生时间',
            'x-order': 1,
            'x-ui': { 'x-list-visible': 'true' },
          },
          level: {
            type: 'string',
            title: '日志级别',
            'x-order': 2,
            'x-ui': { 'x-list-visible': 'true' },
          },
          sourceService: {
            type: 'string',
            title: '源服务',
            'x-order': 3,
            'x-ui': { 'x-list-visible': 'true' },
          },
          loggerName: {
            type: 'string',
            title: '日志器',
            'x-order': 4,
            'x-ui': { 'x-list-visible': 'true' },
          },
          message: {
            type: 'string',
            title: '日志消息',
            'x-order': 5,
            'x-ui': { 'x-list-visible': 'true' },
          },
          exceptionType: {
            type: 'string',
            title: '异常类型',
            'x-order': 6,
            'x-ui': { 'x-list-visible': 'true' },
          },
          tenantId: {
            type: 'string',
            title: '租户ID',
            'x-order': 7,
            'x-ui': { 'x-list-visible': 'true' },
          },
          requestUri: {
            type: 'string',
            title: '请求地址',
            'x-order': 8,
            'x-ui': { 'x-list-visible': 'true' },
          },
          clientIp: {
            type: 'string',
            title: '客户端IP',
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
    const slice = errorLogs.slice(start, start + size);
    return HttpResponse.json({
      content: slice,
      page: {
        size,
        number: page,
        totalElements: errorLogs.length,
        totalPages: Math.ceil(errorLogs.length / size),
      },
    });
  }),
];
