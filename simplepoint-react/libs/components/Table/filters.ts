/** Platform operators can contain colons, as can ISO timestamps and user text. */
const operators = [
  'than:equal:greater', 'than:equal:less', 'than:greater', 'than:less',
  'not:between', 'not:equals', 'not:like', 'not:in', 'between', 'equals', 'like', 'in',
];

export function parseColumnFilter(stored?: string): {op?: string; text: string} {
  if (!stored) return {text: ''};
  if (stored === 'is:null' || stored === 'is:not:null') return {op: stored, text: ''};
  const op = operators.find(operator => stored.startsWith(`${operator}:`));
  return op ? {op, text: stored.slice(op.length + 1)} : {text: ''};
}

export function defaultColumnOperator(type: string): string {
  return ['number', 'integer', 'date', 'boolean'].includes(type) ? 'equals' : 'like';
}
