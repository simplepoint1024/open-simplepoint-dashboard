/** Non-authoritative, account-isolated workspace choices. Never persist authorization context here. */
const KEY = 'sp.preference.workspace.v1';
const identifier = (value: unknown): value is string => typeof value === 'string' && value.length > 0
  && value.length <= 512 && value === value.trim();

export function workspaceUserId(user: unknown): string | undefined {
  if (!user || typeof user !== 'object') return undefined;
  const value = (user as {sub?: unknown; id?: unknown}).sub ?? (user as {id?: unknown}).id;
  return identifier(value) ? value : undefined;
}

function choices(): Record<string, string> {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(KEY) ?? '{}');
    if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
    return Object.fromEntries(Object.entries(value).filter(([user, tenant]) => identifier(user) && identifier(tenant)));
  } catch {return {};}
}

export function rememberedWorkspace(userId: string): string | undefined {
  const values = choices();
  return Object.prototype.hasOwnProperty.call(values, userId) ? values[userId] : undefined;
}

export function rememberWorkspace(userId: string | undefined, tenantId: string | undefined) {
  if (!identifier(userId) || !identifier(tenantId)) return;
  try {localStorage.setItem(KEY, JSON.stringify({...choices(), [userId]: tenantId}));} catch { /* Storage may be disabled. */ }
}

export function selectInitialWorkspace(saved: string | undefined, active: string | undefined, available: string[]) {
  // An invalid remembered choice must not bypass the current membership list.
  if (saved) return available.includes(saved) ? saved : available[0];
  return active && available.includes(active) ? active : available[0];
}

/** Logout/expiry still erase credentials, authorization context and business caches. */
export function clearStorageKeepingWorkspaceChoices() {
  try {
    const saved = choices();
    localStorage.clear();
    if (Object.keys(saved).length) localStorage.setItem(KEY, JSON.stringify(saved));
  } catch { /* Storage may be disabled. */ }
}
