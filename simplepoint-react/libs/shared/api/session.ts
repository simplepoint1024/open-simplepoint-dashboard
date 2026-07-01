import { ensureContextId, getStoredContextId, getStoredTenantId, setStoredContextId, setStoredTenantId } from './contextId';

export type SessionSnapshot = {
  tenantId?: string;
  contextId?: string;
};

const TENANT_EVENT = 'sp-set-tenant';
const CONTEXT_EVENT = 'sp-set-context-id';
type ContextEventDetail = { tenantId?: string; contextId?: string };

export function captureSessionSnapshot(): SessionSnapshot {
  const tenantId = getStoredTenantId();
  return {
    tenantId,
    contextId: getStoredContextId(tenantId),
  };
}

function emitSessionEvents(snapshot: SessionSnapshot) {
  try {
    window.dispatchEvent(new CustomEvent(TENANT_EVENT, { detail: snapshot.tenantId }));
  } catch {}
  try {
    const detail: ContextEventDetail = {
      tenantId: snapshot.tenantId,
      contextId: snapshot.contextId,
    };
    window.dispatchEvent(new CustomEvent(CONTEXT_EVENT, { detail }));
  } catch {}
}

function restoreSessionSnapshot(snapshot?: SessionSnapshot) {
  if (!snapshot) {
    return;
  }
  setStoredTenantId(snapshot.tenantId);
  setStoredContextId(snapshot.contextId, snapshot.tenantId);
  emitSessionEvents(snapshot);
}

async function clearBrowserCaches() {
  try { if (typeof localStorage !== 'undefined') localStorage.clear(); } catch {}
  try { if (typeof sessionStorage !== 'undefined') sessionStorage.clear(); } catch {}

  const tasks: Promise<any>[] = [];

  try {
    if (typeof caches !== 'undefined' && caches?.keys) {
      const keys = await caches.keys();
      tasks.push(Promise.allSettled(keys.map((key) => caches.delete(key))));
    }
  } catch {}

  try {
    if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator && navigator.serviceWorker.getRegistrations) {
      const regs = await navigator.serviceWorker.getRegistrations();
      tasks.push(Promise.allSettled(regs.map((reg) => reg.unregister())));
    }
  } catch {}

  try {
    const idb: any = typeof indexedDB !== 'undefined' ? indexedDB : undefined;
    if (idb && typeof idb.databases === 'function') {
      const dbs = await idb.databases();
      const del = (name: string) => new Promise<void>((resolve) => {
        try {
          const req = indexedDB.deleteDatabase(name);
          req.onsuccess = () => resolve();
          req.onerror = () => resolve();
          req.onblocked = () => resolve();
        } catch {
          resolve();
        }
      });
      tasks.push(Promise.allSettled((dbs || []).map((db: any) => (db?.name ? del(db.name) : Promise.resolve()))));
    }
  } catch {}

  await Promise.allSettled(tasks);
}

export async function clearClientCaches(options?: {
  preserveSessionContext?: boolean;
  rebuildContextId?: boolean;
}) {
  const preserveSessionContext = options?.preserveSessionContext ?? false;
  const snapshot = preserveSessionContext ? captureSessionSnapshot() : undefined;

  await clearBrowserCaches();

  if (!preserveSessionContext) {
    emitSessionEvents({ tenantId: undefined, contextId: undefined });
    return;
  }

  restoreSessionSnapshot(snapshot);

  if (options?.rebuildContextId && snapshot) {
    const nextContextId = await ensureContextId(snapshot.tenantId, { force: true });
    if (nextContextId !== snapshot.contextId) {
      const nextSnapshot = { tenantId: snapshot.tenantId, contextId: nextContextId };
      restoreSessionSnapshot(nextSnapshot);
    }
  }
}

export async function redirectToLogin() {
  await clearClientCaches({ preserveSessionContext: false });
  try {
    window.location.assign('/login');
  } catch {
    try {
      window.location.href = '/login';
    } catch {}
  }
}

export async function redirectToLogout() {
  await clearBrowserCaches();

  const form = document.createElement('form');
  form.method = 'post';
  form.action = '/logout';
  form.style.display = 'none';

  try {
    (document.body ?? document.documentElement).appendChild(form);
  } catch {
    document.documentElement.appendChild(form);
  }

  try {
    form.requestSubmit();
  } catch {
    form.submit();
  }
}
