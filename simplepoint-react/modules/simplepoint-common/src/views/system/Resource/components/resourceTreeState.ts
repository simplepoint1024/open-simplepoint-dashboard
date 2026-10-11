type TreeKey = string | number | bigint;
type Branch<T> = {key: TreeKey; resource?: {hasChildren?: boolean}; children?: T[]};

/** Retained-page revalidation may reuse only children of the exact same real root key. */
export function retainRootChildren<T extends Branch<T>>(previous: T[], incoming: T[], retain: boolean): T[] {
  if (!retain) return incoming;
  const byKey = new Map(previous.map(node => [node.key, node]));
  return incoming.map(node => ({...node,
    ...(node.resource?.hasChildren ? {children: byKey.get(node.key)?.children} : {}),
  }));
}

/** One traversal, not a recursive scan for every loaded key. Pending/failed branches stay retryable. */
export function loadedBranchKeys<T extends Branch<T>>(nodes: T[]): Set<TreeKey> {
  const keys = new Set<TreeKey>(), pending = [...nodes];
  while (pending.length) {
    const node = pending.pop()!;
    if (!node.children?.length) continue;
    keys.add(node.key); pending.push(...node.children);
  }
  return keys;
}

/** Do not rewrite already granted resources when checking another branch. Keep literal server codes. */
export function assignmentDelta(baseline: readonly string[], requested: readonly string[], checked: boolean): string[] {
  const assigned = new Set(baseline);
  return [...new Set(requested)].filter(code => assigned.has(code) !== checked);
}
