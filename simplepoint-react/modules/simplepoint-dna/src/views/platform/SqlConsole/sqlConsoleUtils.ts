/** Preserve business-error details even when an endpoint returns HTTP 200. */
export function assertConsoleResponse(value: unknown, fallback: string): void {
  if (value === null || value === undefined) throw new Error(fallback);
  if (typeof value === 'object' && 'success' in value && value.success === false) {
    throw new Error('message' in value && typeof value.message === 'string' ? value.message : fallback);
  }
}

/** Split only top-level SQL delimiters; quoted strings/comments may contain semicolons. */
export function sqlStatementRanges(sql: string): {from: number; to: number}[] {
  const ranges: {from: number; to: number}[] = [];
  let start = 0;
  let quote = '';
  let dollar = '';
  let blockDepth = 0;
  let lineComment = false;
  for (let i = 0; i < sql.length; i++) {
    const c = sql[i], next = sql[i + 1];
    if (lineComment) { if (c === '\n') lineComment = false; continue; }
    if (blockDepth) {
      if (c === '/' && next === '*') { blockDepth++; i++; }
      else if (c === '*' && next === '/') { blockDepth--; i++; }
      continue;
    }
    if (dollar) {
      if (sql.startsWith(dollar, i)) { i += dollar.length - 1; dollar = ''; }
      continue;
    }
    if (quote) {
      if (c === quote) { if (next === quote) i++; else quote = ''; }
      // PostgreSQL E'...' strings and MySQL backslash-escaped strings.
      else if (c === '\\' && quote === "'") i++;
      continue;
    }
    if (c === '-' && next === '-') { lineComment = true; i++; continue; }
    if (c === '/' && next === '*') { blockDepth = 1; i++; continue; }
    if (c === "'" || c === '"' || c === '`' || c === '[') { quote = c === '[' ? ']' : c; continue; }
    if (c === '$') {
      const match = sql.slice(i).match(/^\$(?:[a-zA-Z_][\w]*)?\$/);
      if (match) { dollar = match[0]; i += dollar.length - 1; continue; }
    }
    if (c === ';') { ranges.push({from: start, to: i + 1}); start = i + 1; }
  }
  if (sql.slice(start).trim()) ranges.push({from: start, to: sql.length});
  return ranges;
}

/** An explicit selection wins; otherwise run the statement containing the caret. */
export function executionSql(sql: string, from: number, to = from): string {
  if (from !== to) return sql.slice(Math.min(from, to), Math.max(from, to)).trim();
  const ranges = sqlStatementRanges(sql);
  const range = ranges.find(item => from >= item.from && from < item.to) ?? ranges[ranges.length - 1];
  return range ? sql.slice(range.from, range.to).trim() : '';
}

export const quoteSqlPath = (segments: string[]) => segments.map(value => `"${value.replace(/"/g, '""')}"`).join('.');

export function filterLoadedTree<T extends {rawTitle?: string; children?: T[]}>(nodes: T[], search: string): T[] {
  const query = search.trim().toLowerCase();
  if (!query) return nodes;
  return nodes.flatMap(node => {
    if (node.rawTitle?.toLowerCase().includes(query)) return [node];
    const children = filterLoadedTree(node.children ?? [], query);
    return children.length ? [{...node, children}] : [];
  });
}

const csvCell = (value: unknown) => {
  let text = value == null ? '' : typeof value === 'object' ? JSON.stringify(value) : String(value);
  // Do not turn untrusted database strings into spreadsheet formulas.
  if (typeof value === 'string' && /^[\s]*[=+@\-]/.test(text)) text = `'${text}`;
  return `"${text.replace(/"/g, '""')}"`;
};

/** Export the displayed snapshot, never re-execute potentially changed SQL. */
export function resultCsv(columns: {name: string}[], rows: unknown[][]): string {
  return '\uFEFF' + [columns.map(column => csvCell(column.name)).join(','),
    ...rows.map(row => columns.map((_, i) => csvCell(row[i])).join(','))].join('\r\n');
}
