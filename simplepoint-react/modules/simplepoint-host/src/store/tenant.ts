import { getStoredTenantId, setStoredTenantId } from '@simplepoint/shared/api/contextId';

export type TenantId = string;

const EVENT_NAME = 'sp-set-tenant';

export function getTenantId(): TenantId | undefined {
  return getStoredTenantId();
}

export function setTenantId(tenantId: TenantId | undefined) {
  setStoredTenantId(tenantId);

  try {
    window.dispatchEvent(new CustomEvent(EVENT_NAME, { detail: tenantId }));
  } catch {}
}

export function onTenantIdChange(handler: (tenantId?: TenantId) => void) {
  const listener = (e: Event) => {
    const tenantId = (e as CustomEvent).detail as TenantId | undefined;
    handler(tenantId);
  };
  window.addEventListener(EVENT_NAME, listener as EventListener);
  return () => window.removeEventListener(EVENT_NAME, listener as EventListener);
}
