import { getStoredRoleId, getStoredTenantId, setStoredRoleId } from '@simplepoint/shared/api/contextId';

export type RoleId = string;

export type RoleChangeDetail = {
  tenantId?: string;
  roleId?: RoleId;
};

const EVENT_NAME = 'sp-set-role';

export function getRoleId(tenantId?: string): RoleId | undefined {
  return getStoredRoleId(tenantId ?? getStoredTenantId());
}

export function setRoleId(roleId: RoleId | undefined, tenantId?: string) {
  const resolvedTenantId = tenantId ?? getStoredTenantId();
  setStoredRoleId(roleId, resolvedTenantId);

  try {
    const detail: RoleChangeDetail = { tenantId: resolvedTenantId, roleId };
    window.dispatchEvent(new CustomEvent(EVENT_NAME, { detail }));
  } catch {}
}

export function onRoleIdChange(handler: (roleId?: RoleId, tenantId?: string) => void) {
  const listener = (e: Event) => {
    const detail = (e as CustomEvent<RoleChangeDetail>).detail;
    if (detail && typeof detail === 'object') {
      handler(detail.roleId, detail.tenantId);
      return;
    }
    handler(detail as RoleId | undefined, getStoredTenantId());
  };
  window.addEventListener(EVENT_NAME, listener as EventListener);
  return () => window.removeEventListener(EVENT_NAME, listener as EventListener);
}
