import type {RoleResourceRow} from '@/api/system/role-resource-configuration';
import type {ResourceDraft} from './resourceDraft';

export type PreviewStatus = 'added' | 'removed' | 'updated' | 'unchanged';
export function previewStatus(entry?: ResourceDraft[string]): PreviewStatus {
  if (!entry) return 'unchanged';
  if (entry.granted !== entry.baseline.granted) return entry.granted ? 'added' : 'removed';
  return 'updated';
}

export interface PreviewNode {key: string; row: RoleResourceRow; children: PreviewNode[]}

/** Retain the selected rows and their ancestors; never infer grants from tree membership. */
export function previewTree(rows: RoleResourceRow[], selectedIds: Set<string>,
  label: (row: RoleResourceRow) => string): PreviewNode[] {
  const byId = new Map(rows.map(row => [row.id, row]));
  const included = new Set<string>();
  for (const id of selectedIds) {
    let row = byId.get(id);
    while (row && !included.has(row.id)) {
      included.add(row.id);
      row = row.parentId ? byId.get(row.parentId) : undefined;
    }
  }
  const nodes = new Map([...included].map(id => [id, {key: id, row: byId.get(id)!, children: []} as PreviewNode]));
  const parents = new Map([...nodes.values()].map(node => [node.key, node.row.parentId]));
  // Malformed legacy cycles must not hang preview or silently remove entire branches.
  const visited = new Set<string>();
  for (const id of nodes.keys()) {
    const path = new Set<string>();
    let current: string | undefined = id;
    while (current && nodes.has(current) && !visited.has(current)) {
      if (path.has(current)) {parents.set(current, undefined); break;}
      path.add(current); current = parents.get(current);
    }
    for (const entry of path) visited.add(entry);
  }
  const roots: PreviewNode[] = [];
  for (const node of nodes.values()) {
    const parent = parents.get(node.key);
    const owner = parent ? nodes.get(parent) : undefined;
    (owner?.children ?? roots).push(node);
  }
  const names = new Map([...nodes.values()].map(node => [node.key, label(node.row)]));
  const compare = (a: PreviewNode, b: PreviewNode) => names.get(a.key)!.localeCompare(names.get(b.key)!);
  roots.sort(compare);
  for (const node of nodes.values()) node.children.sort(compare);
  return roots;
}
