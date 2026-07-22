// 删除未使用的 HttpError import
// (contextId 自动补全是 best-effort，不需要抛自定义错误)

export type TenantId = string;
export type RoleId = string;
export type ContextId = string;
export type ApiScope = 'global' | 'tenant';

// localStorage keys (keep backward compatible)
const KEY_TENANT = 'sp.tenantId';
const KEY_ROLE = 'sp.roleId';
const KEY_ROLE_PREFIX = 'sp.roleId:';
const KEY_CTX = 'sp.contextId';
const KEY_CTX_PREFIX = 'sp.contextId:';

const CTX_ENDPOINT_PATH = '/common/tenants/authorization-context-id';
const API_SCOPE_RULES: ReadonlyArray<{ scope: ApiScope; prefix: string }> = [
  { scope: 'tenant', prefix: '/common/resources/service-routes' },
  { scope: 'global', prefix: '/common/i18n' },
  { scope: 'global', prefix: '/common/logging' },
  { scope: 'global', prefix: '/common/resources' },
  { scope: 'global', prefix: '/common/oidc/clients' },
  { scope: 'global', prefix: '/common/ops/microapps' },
  { scope: 'global', prefix: '/common/tenants/current-roles' },
  { scope: 'global', prefix: '/common/tenants/current' },
  { scope: 'global', prefix: '/common/tenants/page' },
  { scope: 'global', prefix: '/common/users/picker' },
  { scope: 'global', prefix: '/common/platform/dna' },
  { scope: 'global', prefix: '/common/platform/applications' },
  { scope: 'global', prefix: '/common/platform/packages' },
  { scope: 'global', prefix: '/common/platform/dictionaries' },
  { scope: 'global', prefix: '/common/platform/dictionary-items' },
  { scope: 'global', prefix: '/ai/platform/ai' },
  { scope: 'global', prefix: '/common/rate-limit' },
  { scope: 'global', prefix: '/common/redis/entries' },
];

const readLS = (key: string): string | undefined => {
  try {
    const v = localStorage.getItem(key);
    return v || undefined;
  } catch {
    return undefined;
  }
};

const writeLS = (key: string, val: string | undefined) => {
  try {
    if (!val) localStorage.removeItem(key);
    else localStorage.setItem(key, val);
  } catch {}
};

export function getStoredTenantId(): TenantId | undefined {
  return readLS(KEY_TENANT) as TenantId | undefined;
}

export function setStoredTenantId(tenantId: TenantId | undefined) {
  const normalizedTenantId = tenantId?.trim();
  writeLS(KEY_TENANT, normalizedTenantId || undefined);
}

function getRoleStorageKey(tenantId?: TenantId): string {
  return tenantId ? `${KEY_ROLE_PREFIX}${tenantId}` : KEY_ROLE;
}

export function getStoredRoleId(tenantId?: TenantId): RoleId | undefined {
  const normalizedTenantId = tenantId?.trim();
  const tenantRole = normalizedTenantId ? readLS(getRoleStorageKey(normalizedTenantId)) : undefined;
  return (tenantRole || readLS(KEY_ROLE)) as RoleId | undefined;
}

export function setStoredRoleId(roleId: RoleId | undefined, tenantId?: TenantId) {
  const normalizedRoleId = roleId?.trim();
  const normalizedTenantId = tenantId?.trim();
  if (normalizedTenantId) {
    writeLS(getRoleStorageKey(normalizedTenantId), normalizedRoleId || undefined);
  }
  writeLS(KEY_ROLE, normalizedRoleId || undefined);
}

function getContextStorageKey(tenantId?: TenantId, roleId?: RoleId): string {
  const normalizedTenantId = tenantId?.trim();
  const normalizedRoleId = roleId?.trim();
  if (normalizedTenantId && normalizedRoleId) {
    return `${KEY_CTX_PREFIX}${normalizedTenantId}:role:${normalizedRoleId}`;
  }
  return normalizedTenantId ? `${KEY_CTX_PREFIX}${normalizedTenantId}` : KEY_CTX;
}

export function getStoredContextId(tenantId?: TenantId, roleId?: RoleId): ContextId | undefined {
  return readLS(getContextStorageKey(tenantId, roleId)) as ContextId | undefined;
}

export function setStoredContextId(contextId: ContextId | undefined, tenantId?: TenantId, roleId?: RoleId) {
  writeLS(getContextStorageKey(tenantId, roleId), contextId);
  writeLS(KEY_CTX, contextId);
}

function isContextIdEndpoint(url: string): boolean {
  try {
    const u = new URL(
      url,
      typeof window !== 'undefined' ? window.location.origin : 'http://localhost'
    );
    return u.pathname === CTX_ENDPOINT_PATH;
  } catch {
    return url.includes(CTX_ENDPOINT_PATH);
  }
}

function resolvePathname(url: string): string | undefined {
  try {
    return new URL(
      url,
      typeof window !== 'undefined' ? window.location.origin : 'http://localhost'
    ).pathname;
  } catch {
    return undefined;
  }
}

export function shouldUseTenantContext(url: string): boolean {
  const pathname = resolvePathname(url);
  if (!pathname) {
    return resolveApiScope(url) === 'tenant';
  }
  return resolveApiScope(pathname) === 'tenant';
}

export function resolveApiScope(url: string): ApiScope {
  const pathname = resolvePathname(url) ?? url;
  const matchedRule = API_SCOPE_RULES.find(
    ({ prefix }) => pathname === prefix || pathname.startsWith(`${prefix}/`) || url.includes(prefix)
  );
  return matchedRule?.scope ?? 'tenant';
}

function parseContextId(text: string, contentType: string | null): string | undefined {
  let ctx = (text || '').toString();
  const ct = contentType || '';
  if (ct.includes('application/json')) {
    try {
      const json: any = text ? JSON.parse(text) : undefined;
      ctx = typeof json === 'string' ? json : json?.contextId;
    } catch {
      // ignore parse error, fallback to raw text
    }
  }
  const finalCtx = ctx.trim();
  return finalCtx ? finalCtx : undefined;
}

// Per-tenant in-flight promise dedupe (module local, not global)
const inflight = new Map<string, Promise<string | undefined>>();

export async function ensureContextId(
  tenantId: TenantId | undefined,
  opts?: { force?: boolean; signal?: AbortSignal; throwOnError?: boolean; roleId?: RoleId }
): Promise<ContextId | undefined> {
  const normalizedTenantId = tenantId?.trim();
  if (!normalizedTenantId) {
    return undefined;
  }
  const normalizedRoleId = (opts?.roleId ?? getStoredRoleId(normalizedTenantId))?.trim();

  // If not force, reuse stored value
  if (!opts?.force) {
    const cached = getStoredContextId(normalizedTenantId, normalizedRoleId);
    if (cached) return cached;
  }

  const key = normalizedRoleId ? `${normalizedTenantId}:${normalizedRoleId}` : normalizedTenantId;
  const existing = inflight.get(key);
  if (existing) return existing;

  const p = (async () => {
    try {
      const roleQuery = normalizedRoleId ? `&roleId=${encodeURIComponent(normalizedRoleId)}` : '';
      const url = `${CTX_ENDPOINT_PATH}?tenantId=${encodeURIComponent(normalizedTenantId)}${roleQuery}`;

      const res = await fetch(url, {
        method: 'GET',
        credentials: 'include',
        signal: opts?.signal,
        headers: {
          'Content-Type': 'application/json',
          'X-Tenant-Id': normalizedTenantId,
          ...(normalizedRoleId ? { 'X-Role-Id': normalizedRoleId } : {}),
        },
      });

      if (!res.ok) {
        return undefined;
      }

      const text = await res.text();
      const ctx = parseContextId(text, res.headers.get('content-type'));
      if (ctx) setStoredContextId(ctx, normalizedTenantId, normalizedRoleId);
      return ctx;
    } catch (e) {
      if (opts?.throwOnError) throw e;
      return undefined;
    } finally {
      inflight.delete(key);
    }
  })();

  inflight.set(key, p);
  return p;
}

export function shouldAutoEnsureContextId(url: string, headerContextId: any) {
  if (!shouldUseTenantContext(url)) return false;
  if (isContextIdEndpoint(url)) return false;
  return (
    headerContextId == null ||
    headerContextId === '' ||
    (typeof headerContextId === 'string' && headerContextId.trim() === '')
  );
}
