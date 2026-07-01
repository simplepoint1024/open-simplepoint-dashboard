import { http, HttpResponse } from 'msw';

/**
 * GET /common/tenants/current
 * 返回当前用户可切换的租户列表
 */
export default [
  http.get('/common/tenants/current', () => {
    return HttpResponse.json([
      { tenantId: 'tenant-001', tenantName: '默认租户' },
      { tenantId: 'tenant-002', tenantName: '演示租户' },
      { tenantId: 'tenant-003', tenantName: '测试租户' },
    ]);
  }),

  /**
   * GET /common/tenants/permission-context-id?tenantId=
   * 切换租户时用于获取权限上下文（按你的描述：返回结果“和租户一样”的结构）
   */
  http.get('/common/tenants/permission-context-id', ({ request }) => {
    const url = new URL(request.url);
    const tenantId = url.searchParams.get('tenantId') || 'tenant-001';
    return HttpResponse.text(`ctx-${tenantId}`);
  }),
];
