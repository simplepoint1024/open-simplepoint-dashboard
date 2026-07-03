import { http, HttpResponse } from 'msw';

/**
 * GET /common/tenants/current
 * 返回当前用户可切换的租户列表
 */
export default [
  http.get('/common/tenants/current', () => {
    return HttpResponse.json([
      { tenantId: 'tenant-001', tenantName: '默认租户', tenantType: 'ORGANIZATION' },
      { tenantId: 'tenant-002', tenantName: '演示租户', tenantType: 'ORGANIZATION' },
      { tenantId: 'tenant-003', tenantName: '测试租户', tenantType: 'ORGANIZATION' },
      { tenantId: 'tenant-personal', tenantName: '系统用户 的个人空间', tenantType: 'PERSONAL' },
    ]);
  }),

  http.get('/common/tenants/current-roles', ({ request }) => {
    const url = new URL(request.url);
    const tenantId = url.searchParams.get('tenantId') || 'tenant-001';
    if (tenantId === 'tenant-personal') {
      return HttpResponse.json([
        { id: 'role-personal-owner', authority: 'PERSONAL_OWNER' },
      ]);
    }
    return HttpResponse.json([
      { id: 'role-admin', authority: 'TENANT_ADMIN' },
      { id: 'role-member', authority: 'TENANT_MEMBER' },
    ]);
  }),

  /**
   * GET /common/tenants/permission-context-id?tenantId=
   * 切换租户时用于获取权限上下文（按你的描述：返回结果“和租户一样”的结构）
   */
  http.get('/common/tenants/permission-context-id', ({ request }) => {
    const url = new URL(request.url);
    const tenantId = url.searchParams.get('tenantId') || 'tenant-001';
    const roleId = url.searchParams.get('roleId');
    return HttpResponse.text(roleId ? `ctx-${tenantId}-${roleId}` : `ctx-${tenantId}`);
  }),
];
