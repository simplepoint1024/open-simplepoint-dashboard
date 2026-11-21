const recordIdentity = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const maxCollectionMappings = 16_000;
export const maxCollectionMappingTextLength = 1_280_000;

export function validCollectionMapping(mapping: Record<string, string>): boolean {
  if (!mapping || typeof mapping !== 'object' || Array.isArray(mapping)) return false;
  const pairs = Object.entries(mapping);
  return pairs.length <= maxCollectionMappings && pairs.every(([from, to]) =>
    recordIdentity.test(from) && typeof to === 'string' && recordIdentity.test(to));
}

export function parseCollectionMapping(text: string): Record<string, string> {
  if (text.length > maxCollectionMappingTextLength) throw new Error('Collection mapping is too large');
  const pairs = new Map<string, string>();
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim()) continue;
    const [from, to, extra] = line.split(',').map(id => id.trim().toLowerCase());
    if (!from || !to || extra !== undefined || !recordIdentity.test(from)
      || !recordIdentity.test(to) || pairs.has(from) || pairs.size === maxCollectionMappings)
      throw new Error('Invalid collection mapping');
    pairs.set(from, to);
  }
  return Object.fromEntries(pairs);
}
