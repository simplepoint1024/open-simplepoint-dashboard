/** Read-only test probe. A DOM node's cached fiber may point at React's previous commit. */
export function committedReactFiber(element) {
  const key = Object.keys(element ?? {}).find(name => name.startsWith('__reactFiber$'));
  let root = key && element[key];
  if (!root) throw Error('React DOM fiber missing');
  while (root.return) root = root.return;
  const current = root.stateNode?.current;
  if (!current) throw Error('Committed React root missing');
  const pending = [current];
  while (pending.length) {
    const node = pending.pop();
    if (node.stateNode === element) return node;
    if (node.sibling) pending.push(node.sibling);
    if (node.child) pending.push(node.child);
  }
  throw Error('DOM node is not in the committed React tree');
}
