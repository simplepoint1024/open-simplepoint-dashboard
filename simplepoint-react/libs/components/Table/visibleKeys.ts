/** Only the visible field identifiers define column-preference initialization, not translated labels. */
export function visibleKeySignature(properties: Record<string, any>): string {
  const keys = Object.keys(properties), visible: string[] = [];
  let declared = false;
  for (const key of keys) {
    const raw = properties[key]?.['x-ui']?.['x-list-visible'];
    const flag = raw === true || raw === 1 || raw === 'true' || raw === '1' ? true
      : raw === false || raw === 0 || raw === 'false' || raw === '0' ? false : undefined;
    if (flag !== undefined) {declared = true; if (flag) visible.push(key);}
  }
  return JSON.stringify(declared ? visible : keys);
}
