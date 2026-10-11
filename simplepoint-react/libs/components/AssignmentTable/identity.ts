/** Exact readable keys only; protected identities cannot become assignment payloads. */
export function assignmentKey(value: unknown): string | undefined {
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : undefined;
  return typeof value === 'string' && value.trim() && !/^\*+$/.test(value.trim()) ? value : undefined;
}

/** Presentation keys belong only to this bounded page; they are never read back as selected identifiers. */
export function assignmentPresentationKeys<T extends object>(rows: readonly T[], field: keyof T) {
  const used = new Set(rows.map(row => assignmentKey(row[field])).filter(key => key !== undefined));
  const result = new Map<T, string>();
  let index = 0;
  for (const row of rows) {
    let key = assignmentKey(row[field]);
    if (key === undefined) {
      do {key = '__assignment_presentation__' + index++;} while (used.has(key));
      used.add(key);
    }
    result.set(row, key);
  }
  return result;
}
