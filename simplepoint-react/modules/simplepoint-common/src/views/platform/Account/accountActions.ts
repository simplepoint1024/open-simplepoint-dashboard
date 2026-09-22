const roles = new Set(['PLATFORM_ADMIN', 'ACCOUNT_ADMIN', 'AUDITOR']);
const permissions: Record<string, string> = {
  add: 'platform.accounts.create', edit: 'platform.accounts.edit', 'config.identity': 'platform.identity.manage',
};

/** Never initialize destructive identity edits from hidden or masked fields. */
export function accountActionDisabled(action: string, rows: readonly {
  id?: unknown; revision?: unknown; name?: unknown; enabled?: unknown; superAdmin?: unknown; roles?: unknown;
}[], grants?: readonly string[]) {
  if (!permissions[action] || !grants?.includes(permissions[action])) return true;
  if (action === 'add') return false;
  if (rows.length !== 1) return true;
  const row = rows[0];
  if (typeof row.id !== 'string' || !row.id || row.id === '***' || typeof row.revision !== 'number'
    || !Number.isSafeInteger(row.revision) || row.revision < 0) return true;
  return action === 'config.identity'
    ? typeof row.superAdmin !== 'boolean' || !Array.isArray(row.roles) || !row.roles.every(role => roles.has(role))
    : typeof row.enabled !== 'boolean' || !(row.name === null || (typeof row.name === 'string' && row.name !== '***'));
}
