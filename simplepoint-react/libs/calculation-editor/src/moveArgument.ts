import type {FormulaNode} from '@simplepoint/calculation';

/** Reorders siblings without changing arity or discarding the argument displaced by a move. */
export function moveArgument(root: FormulaNode, path: readonly number[], direction: -1 | 1):
  {tree: FormulaNode; path: readonly number[]} | undefined {
  if (!path.length || path.some(index => !Number.isSafeInteger(index) || index < 0)) return;
  const parentPath = path.slice(0, -1), from = path[path.length - 1], to = from + direction;
  let parent = root;
  for (const index of parentPath) {parent = parent.children[index]; if (!parent) return;}
  if (from >= parent.children.length || to < 0 || to >= parent.children.length) return;
  const children = [...parent.children];
  [children[from], children[to]] = [children[to], children[from]];
  const replace = (node: FormulaNode, depth: number): FormulaNode => depth === parentPath.length
    ? {...node, children}
    : {...node, children: node.children.map((child, index) => index === parentPath[depth] ? replace(child, depth + 1) : child)};
  return {tree: replace(root, 0), path: [...parentPath, to]};
}
