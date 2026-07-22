import { http, HttpResponse } from 'msw';

const currentTenants = [
  {tenantId: 'tenant-001', tenantName: '默认租户', tenantType: 'ORGANIZATION', tenantLogo: '/svg.svg'},
  {tenantId: 'tenant-002', tenantName: '演示租户', tenantType: 'ORGANIZATION'},
  {tenantId: 'tenant-003', tenantName: '测试租户', tenantType: 'ORGANIZATION'},
  {tenantId: 'tenant-personal', tenantName: '系统用户 的个人空间', tenantType: 'PERSONAL'},
];

const profiles: Record<string, Record<string, unknown>> = Object.fromEntries(
  currentTenants.map((tenant) => [tenant.tenantId, {
    id: tenant.tenantId,
    name: tenant.tenantName,
    description: `${tenant.tenantName}的租户主页`,
    logo: tenant.tenantLogo,
    tenantType: tenant.tenantType,
    ownerId: 'admin',
    ownerName: '平台管理员',
    ownerGender: '保密',
    ownerPhoneNumber: '13800000001',
    ownerEmail: 'admin@example.com',
    profileEditable: true,
  }]),
);

/**
 * GET /common/tenants/current
 * 返回当前用户可切换的租户列表
 */
export default [
  http.get('/common/tenants/current', () => {
    return HttpResponse.json(currentTenants);
  }),

  http.get('/common/tenants/current-profile', ({request}) => {
    const tenantId = request.headers.get('X-Tenant-Id') || 'tenant-001';
    return HttpResponse.json(profiles[tenantId] ?? profiles['tenant-001']);
  }),

  http.put('/common/tenants/current-profile', async ({request}) => {
    const tenantId = request.headers.get('X-Tenant-Id') || 'tenant-001';
    const payload = await request.json() as Record<string, unknown>;
    profiles[tenantId] = {...(profiles[tenantId] ?? {}), ...payload};
    const tenant = currentTenants.find(item => item.tenantId === tenantId);
    if (tenant) {
      tenant.tenantName = String(payload.name ?? tenant.tenantName);
      tenant.tenantLogo = typeof payload.logo === 'string' ? payload.logo : undefined;
    }
    return HttpResponse.json(profiles[tenantId]);
  }),

  http.get('/common/tenants/current-roles', ({ request }) => {
    const url = new URL(request.url);
    const tenantId = url.searchParams.get('tenantId') || 'tenant-001';
    if (tenantId === 'tenant-personal') {
      return HttpResponse.json([
        { id: 'role-personal-owner', name: '个人空间所有者', authority: 'PERSONAL_OWNER' },
      ]);
    }
    return HttpResponse.json([
      { id: 'role-admin', name: '租户管理员', authority: 'TENANT_ADMIN' },
      { id: 'role-member', name: '租户成员', authority: 'TENANT_MEMBER' },
    ]);
  }),

  /**
   * GET /common/tenants/authorization-context-id?tenantId=
   * 切换租户时用于获取授权上下文（按你的描述：返回结果“和租户一样”的结构）
   */
  http.get('/common/tenants/authorization-context-id', ({ request }) => {
    const url = new URL(request.url);
    const tenantId = url.searchParams.get('tenantId') || 'tenant-001';
    const roleId = url.searchParams.get('roleId');
    return HttpResponse.text(roleId ? `ctx-${tenantId}-${roleId}` : `ctx-${tenantId}`);
  }),
];
