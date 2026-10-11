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

const hasSqlToken = (value: string): boolean => {
  let blockDepth = 0;
  let lineComment = false;
  for (let index = 0; index < value.length; index += 1) {
    const current = value[index];
    const next = value[index + 1];
    if (lineComment) {
      if (current === '\n') lineComment = false;
      continue;
    }
    if (blockDepth > 0) {
      if (current === '/' && next === '*') { blockDepth += 1; index += 1; }
      else if (current === '*' && next === '/') { blockDepth -= 1; index += 1; }
      continue;
    }
    if (current === '-' && next === '-') { lineComment = true; index += 1; continue; }
    if (current === '/' && next === '*') { blockDepth = 1; index += 1; continue; }
    if (!/\s/.test(current) && current !== ';') return true;
  }
  return false;
};

/** Return executable statements while preserving comments attached to each statement. */
export function sqlStatements(sql: string): string[] {
  return sqlStatementRanges(sql)
    .map(range => sql.slice(range.from, range.to).trim())
    .filter(hasSqlToken);
}

/** Locate the statement containing the caret while retaining document offsets. */
export function sqlStatementAt(sql: string, position: number): {from: number; to: number; text: string} {
  const safePosition = Math.max(0, Math.min(position, sql.length));
  const ranges = sqlStatementRanges(sql);
  const range = ranges.find(item => safePosition >= item.from && safePosition < item.to)
    ?? ranges.find(item => safePosition === item.to)
    ?? {from: safePosition, to: safePosition};
  return {...range, text: sql.slice(range.from, range.to)};
}

/** An explicit selection wins; otherwise run the statement containing the caret. */
export function executionSql(sql: string, from: number, to = from): string {
  if (from !== to) return sql.slice(Math.min(from, to), Math.max(from, to)).trim();
  const ranges = sqlStatementRanges(sql);
  const range = ranges.find(item => from >= item.from && from < item.to) ?? ranges[ranges.length - 1];
  return range ? sql.slice(range.from, range.to).trim() : '';
}

export const quoteSqlPath = (segments: string[]) => segments.map(value => `"${value.replace(/"/g, '""')}"`).join('.');

export type SqlTemplateKind = 'SELECT' | 'INSERT' | 'UPDATE' | 'DELETE';

export type SqlTemplateColumn = {
  name: string;
  autoIncrement?: boolean | null;
};

export type SqlTemplateConstraint = {
  type: string;
  columns?: string[] | null;
};

export type SqlTemplateStructure = {
  columns?: SqlTemplateColumn[] | null;
  constraints?: SqlTemplateConstraint[] | null;
};

export type SqlTemplateOptions = {
  missingPrimaryKeyComment?: string;
};

const quoteIdentifier = (value: string) => quoteSqlPath([value]);

const indentedList = (values: string[]) => values.map(value => `  ${value}`).join(',\n');

/** Build editable, parameterized CRUD SQL from the table metadata exposed by DNA. */
export function buildTableSqlTemplate(
  kind: SqlTemplateKind,
  path: string[],
  structure?: SqlTemplateStructure | null,
  options?: SqlTemplateOptions,
): string {
  const table = quoteSqlPath(path);
  if (kind === 'SELECT') {
    return `SELECT *\nFROM ${table};`;
  }

  const columns = (structure?.columns ?? []).filter(column => Boolean(column.name));
  if (kind === 'INSERT') {
    const insertColumns = columns.filter(column => column.autoIncrement !== true);
    if (insertColumns.length === 0) {
      return `INSERT INTO ${table}\nDEFAULT VALUES;`;
    }
    return `INSERT INTO ${table} (\n${indentedList(insertColumns.map(column => quoteIdentifier(column.name)))}\n) VALUES (\n${indentedList(insertColumns.map(() => '?'))}\n);`;
  }

  const primaryKey = (structure?.constraints ?? [])
    .find(constraint => constraint.type?.toUpperCase() === 'PRIMARY_KEY')
    ?.columns?.filter(Boolean) ?? [];
  const primaryKeyNames = new Set(primaryKey);
  const mutableColumns = columns.filter(column => !primaryKeyNames.has(column.name));
  const updateColumns = mutableColumns.length > 0 ? mutableColumns : columns;
  const whereClause = primaryKey.length > 0
    ? primaryKey.map((column, index) => `${index === 0 ? 'WHERE' : '  AND'} ${quoteIdentifier(column)} = ?`).join('\n')
    : `WHERE 1 = 0; -- ${options?.missingPrimaryKeyComment || 'TODO: add a precise filter before execution'}`;

  if (kind === 'UPDATE') {
    const assignments = updateColumns.length > 0
      ? indentedList(updateColumns.map(column => `${quoteIdentifier(column.name)} = ?`))
      : '  "column_name" = ?';
    return `UPDATE ${table}\nSET\n${assignments}\n${whereClause}${primaryKey.length > 0 ? ';' : ''}`;
  }

  return `DELETE FROM ${table}\n${whereClause}${primaryKey.length > 0 ? ';' : ''}`;
}

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
