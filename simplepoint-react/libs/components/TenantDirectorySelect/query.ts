const roleBlank = /^[\u0009-\u000D\u001C-\u0020\u1680\u2000-\u2006\u2008-\u200A\u2028\u2029\u205F\u3000]*$/u;
const roleOuterWhitespace = /^[\u0000-\u0020]|[\u0000-\u0020]$/;
const roleDelimiterOrControl = /[,\u0000-\u001F\u007F-\u009F]/;

/** Matches the backend's role-value/CSV contract without treating Java-accepted Unicode spaces as JS trim. */
export function tenantRoleAuthoritySelectable(value: string): boolean {
  return value.length <= 100 && !roleBlank.test(value)
    && !roleOuterWhitespace.test(value) && !roleDelimiterOrControl.test(value);
}

/** Domain selected-value endpoint accepts CSV, with both a 50-value and request-line bound. */
export function tenantDirectoryLookupBatches(ids: readonly string[]): string[][] {
  const batches: string[][] = [];
  let current: string[] = [];
  for (const id of [...new Set(ids)]) {
    const next = [...current, id];
    const wire = new URLSearchParams({values: next.join(',')}).toString();
    if (current.length && (next.length > 50 || wire.length > 2500)) {
      batches.push(current); current = [id];
    } else current = next;
  }
  if (current.length) batches.push(current);
  return batches;
}
