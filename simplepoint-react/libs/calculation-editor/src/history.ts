/** Bounded expression history includes invalid text drafts and fences host-owned replacements. */
export type ExpressionHistory = {observed: string; present: string; past: readonly string[]; future: readonly string[]};
const LIMIT = 100;
export const expressionHistory = (value: string): ExpressionHistory => ({observed: value, present: value, past: [], future: []});
export function reconcileHistory(history: ExpressionHistory, value: string): ExpressionHistory {
  if (value === history.observed) return history;
  return value === history.present ? {...history, observed: value} : expressionHistory(value);
}
export function changeExpression(history: ExpressionHistory, value: string, visible = history.present): ExpressionHistory {
  history = reconcileHistory(history, visible);
  if (history.present !== visible) history = expressionHistory(visible);
  return value === history.present ? history : {...history, present: value,
    past: [...history.past, history.present].slice(-LIMIT), future: []};
}
export function traverseHistory(history: ExpressionHistory, direction: 'undo' | 'redo'): ExpressionHistory {
  const source = direction === 'undo' ? history.past : history.future;
  if (!source.length) return history;
  return direction === 'undo'
    ? {...history, present: source[source.length - 1], past: source.slice(0, -1), future: [...history.future, history.present].slice(-LIMIT)}
    : {...history, present: source[source.length - 1], future: source.slice(0, -1), past: [...history.past, history.present].slice(-LIMIT)};
}
