/** Presentation fallback keys must never become identifiers for server actions. */
export function hasRecordIdentity(record: unknown): boolean {
  if (!record || typeof record !== 'object') return false;
  const row = record as {id?: unknown; key?: unknown};
  const value = row.id ?? row.key;
  return typeof value === 'number' && Number.isFinite(value)
    || typeof value === 'string' && value.trim().length > 0 && value !== '***';
}
