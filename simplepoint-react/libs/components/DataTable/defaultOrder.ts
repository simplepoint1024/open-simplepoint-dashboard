/** Never reorder a server page or an explicitly ordered result. Creation time is the only inferred chronology. */
export function newestFirst<T extends object>(rows: readonly T[], keys: readonly string[]): readonly T[] {
  if (keys.some(key => ['sort', 'sortOrder', 'sequence', 'ordinal', 'position', 'row_order', 'rowNumber', 'index'].includes(key))) return rows;
  const created = (row: T) => (row as Record<string, unknown>).createdAt ?? (row as Record<string, any>).systemValues?.createdAt;
  if (!rows.length || !rows.every(row => typeof created(row) === 'string' && Number.isFinite(Date.parse(created(row))))) return rows;
  return [...rows].sort((a, b) => Date.parse(created(b)) - Date.parse(created(a)));
}
