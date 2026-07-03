import { getStoredContextId, getStoredRoleId, getStoredTenantId, setStoredContextId } from '@simplepoint/shared/api/contextId';

export type ContextId = string;
export type ContextChangeDetail = { tenantId?: string; roleId?: string; contextId?: ContextId };

const EVENT_NAME = 'sp-set-context-id';

export function getContextId(tenantId?: string, roleId?: string): ContextId | undefined {
  const resolvedTenantId = tenantId ?? getStoredTenantId();
  return getStoredContextId(resolvedTenantId, roleId ?? getStoredRoleId(resolvedTenantId));
}

export function setContextId(contextId: ContextId | undefined, tenantId?: string, roleId?: string) {
  const resolvedTenantId = tenantId ?? getStoredTenantId();
  const resolvedRoleId = roleId ?? getStoredRoleId(resolvedTenantId);
  setStoredContextId(contextId, resolvedTenantId, resolvedRoleId);

  try {
    const detail: ContextChangeDetail = { tenantId: resolvedTenantId, roleId: resolvedRoleId, contextId };
    window.dispatchEvent(new CustomEvent(EVENT_NAME, { detail }));
  } catch {}
}

export function onContextIdChange(handler: (contextId?: ContextId, tenantId?: string) => void) {
  const listener = (e: Event) => {
    const detail = (e as CustomEvent<ContextChangeDetail>).detail;
    if (detail && typeof detail === 'object') {
      handler(detail.contextId, detail.tenantId);
      return;
    }
    handler(detail as ContextId | undefined, getStoredTenantId());
  };
  window.addEventListener(EVENT_NAME, listener as EventListener);
  return () => window.removeEventListener(EVENT_NAME, listener as EventListener);
}
