import { getStoredContextId, getStoredTenantId, setStoredContextId } from '@simplepoint/shared/api/contextId';

export type ContextId = string;
export type ContextChangeDetail = { tenantId?: string; contextId?: ContextId };

const EVENT_NAME = 'sp-set-context-id';

export function getContextId(tenantId?: string): ContextId | undefined {
  return getStoredContextId(tenantId ?? getStoredTenantId());
}

export function setContextId(contextId: ContextId | undefined, tenantId?: string) {
  const resolvedTenantId = tenantId ?? getStoredTenantId();
  setStoredContextId(contextId, resolvedTenantId);

  try {
    const detail: ContextChangeDetail = { tenantId: resolvedTenantId, contextId };
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
