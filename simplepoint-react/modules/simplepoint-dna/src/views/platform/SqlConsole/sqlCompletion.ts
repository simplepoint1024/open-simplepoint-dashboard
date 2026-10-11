import {sqlStatementAt} from './sqlConsoleUtils.ts';

export type SqlCompletionPathSegment = {
  type: 'ROOT' | 'DATABASE' | 'CATALOG' | 'SCHEMA' | 'TABLE' | 'VIEW' | 'COLUMN';
  name: string;
};

export type SqlCompletionRelation = {
  path: SqlCompletionPathSegment[];
  type: 'TABLE' | 'VIEW';
  remarks?: string | null;
};

export type SqlCompletionCatalog = {
  dialectCode?: string | null;
  dialectName?: string | null;
  identifierQuote?: string | null;
  currentCatalog?: string | null;
  currentSchema?: string | null;
  relations: SqlCompletionRelation[];
};

export type SqlCompletionColumn = {
  name: string;
  typeName?: string | null;
  size?: number | null;
  scale?: number | null;
  nullable?: boolean | null;
  remarks?: string | null;
};

export type SqlCompletionStructure = {
  columns?: SqlCompletionColumn[] | null;
};

export type SqlCompletionDataSource = {
  id: string;
  code: string;
  name?: string;
  databaseProductName?: string;
};

export type SqlCompletionMetadataProvider = {
  dataSources: readonly SqlCompletionDataSource[];
  catalogCode?: string;
  defaultSchema?: string;
  loadCatalog: (dataSourceId: string) => Promise<SqlCompletionCatalog>;
  loadStructure: (
    dataSourceId: string,
    path: SqlCompletionPathSegment[],
  ) => Promise<SqlCompletionStructure>;
};

export type SqlCompletionSuggestion = {
  label: string;
  apply?: string;
  type: 'namespace' | 'table' | 'view' | 'field' | 'variable';
  detail?: string;
  info?: string;
  boost?: number;
  metadataTarget?: SqlMetadataTarget;
};

export type SqlMetadataTarget = {
  dataSourceId: string;
  path: SqlCompletionPathSegment[];
};

export type SqlResolvedSymbol = {
  from: number;
  to: number;
  suggestion: SqlCompletionSuggestion;
};

type SqlToken = {
  kind: 'identifier' | 'symbol';
  value: string;
  upper: string;
  from: number;
  to: number;
  depth: number;
  quoted?: boolean;
};

export type SqlRelationBinding = {
  alias: string;
  path: string[];
  columns?: string[];
};

export type SqlCompletionAnalysis = {
  blocked: boolean;
  from: number;
  prefix: string;
  qualifier: string[];
  expected: 'relation' | 'qualified' | 'expression';
  bindings: SqlRelationBinding[];
  ctes: Array<{name: string; columns: string[]}>;
  selectAliases: string[];
};

const identifierPart = /[\p{L}\p{Nl}\p{N}\p{Mn}\p{Mc}_$]/u;
const identifierStart = /[\p{L}\p{Nl}_$]/u;
const clauseEnd = new Set([
  'WHERE', 'GROUP', 'HAVING', 'ORDER', 'LIMIT', 'OFFSET', 'FETCH', 'UNION',
  'INTERSECT', 'EXCEPT', 'RETURNING', 'SET', 'VALUES',
]);
const reservedAliasWords = new Set([
  ...clauseEnd,
  'AS', 'ON', 'USING', 'JOIN', 'LEFT', 'RIGHT', 'FULL', 'INNER', 'OUTER',
  'CROSS', 'NATURAL', 'AND', 'OR', 'WHEN', 'THEN', 'ELSE', 'END',
]);
const reservedIdentifiers = new Set([
  'ALL', 'ALTER', 'AND', 'AS', 'ASC', 'BETWEEN', 'BY', 'CASE', 'CAST', 'CHECK',
  'COLUMN', 'CREATE', 'CROSS', 'CURRENT_DATE', 'CURRENT_TIME', 'CURRENT_TIMESTAMP',
  'DELETE', 'DESC', 'DISTINCT', 'DROP', 'ELSE', 'END', 'EXCEPT', 'EXISTS', 'FALSE',
  'FETCH', 'FOREIGN', 'FROM', 'FULL', 'GROUP', 'HAVING', 'IN', 'INNER', 'INSERT',
  'INTERSECT', 'INTO', 'IS', 'JOIN', 'LEFT', 'LIKE', 'LIMIT', 'NOT', 'NULL', 'OFFSET',
  'ON', 'OR', 'ORDER', 'OUTER', 'PRIMARY', 'REFERENCES', 'RIGHT', 'SELECT', 'SET',
  'TABLE', 'THEN', 'TRUE', 'UNION', 'UNIQUE', 'UPDATE', 'USING', 'VALUES', 'VIEW',
  'WHEN', 'WHERE', 'WITH',
]);

const unquoteIdentifier = (raw: string) => {
  if (raw.length < 2) return ['"', '`', '['].includes(raw[0]) ? '' : raw;
  const first = raw[0];
  const last = raw[raw.length - 1];
  if ((first === '"' && last === '"') || (first === '`' && last === '`')) {
    return raw.slice(1, -1).replace(new RegExp(`${first}${first}`, 'g'), first);
  }
  if (first === '[' && last === ']') return raw.slice(1, -1).replace(/]]/g, ']');
  return ['"', '`', '['].includes(first) ? raw.slice(1) : raw;
};

const tokenizeSql = (sql: string, cursor: number): {tokens: SqlToken[]; blocked: boolean} => {
  const tokens: SqlToken[] = [];
  let blocked = false;
  let depth = 0;
  let index = 0;
  const markBlocked = (from: number, to: number) => {
    if (cursor > from && cursor <= to) blocked = true;
  };
  while (index < sql.length) {
    const char = sql[index];
    const next = sql[index + 1];
    if (/\s/u.test(char)) {
      index += 1;
      continue;
    }
    if (char === '-' && next === '-') {
      const from = index;
      index += 2;
      while (index < sql.length && sql[index] !== '\n') index += 1;
      markBlocked(from, index);
      continue;
    }
    if (char === '/' && next === '*') {
      const from = index;
      let blockDepth = 1;
      index += 2;
      while (index < sql.length && blockDepth > 0) {
        if (sql[index] === '/' && sql[index + 1] === '*') {
          blockDepth += 1;
          index += 2;
        } else if (sql[index] === '*' && sql[index + 1] === '/') {
          blockDepth -= 1;
          index += 2;
        } else {
          index += 1;
        }
      }
      markBlocked(from, index);
      continue;
    }
    if (char === "'") {
      const from = index++;
      while (index < sql.length) {
        if (sql[index] === '\\') {
          index += 2;
        } else if (sql[index] === "'" && sql[index + 1] === "'") {
          index += 2;
        } else if (sql[index] === "'") {
          index += 1;
          break;
        } else {
          index += 1;
        }
      }
      markBlocked(from, index);
      continue;
    }
    if (char === '$') {
      const tag = sql.slice(index).match(/^\$(?:[a-zA-Z_][\w]*)?\$/)?.[0];
      if (tag) {
        const from = index;
        index += tag.length;
        const closing = sql.indexOf(tag, index);
        index = closing < 0 ? sql.length : closing + tag.length;
        markBlocked(from, index);
        continue;
      }
    }
    if (char === '"' || char === '`' || char === '[') {
      const from = index;
      const closing = char === '[' ? ']' : char;
      index += 1;
      while (index < sql.length) {
        if (sql[index] === closing && sql[index + 1] === closing) {
          index += 2;
        } else if (sql[index] === closing) {
          index += 1;
          break;
        } else {
          index += 1;
        }
      }
      const raw = sql.slice(from, index);
      const value = unquoteIdentifier(raw);
      tokens.push({kind: 'identifier', value, upper: value.toUpperCase(), from, to: index, depth, quoted: true});
      continue;
    }
    if (identifierStart.test(char) || /[0-9]/u.test(char)) {
      const from = index++;
      while (index < sql.length && identifierPart.test(sql[index])) index += 1;
      const value = sql.slice(from, index);
      tokens.push({kind: 'identifier', value, upper: value.toUpperCase(), from, to: index, depth});
      continue;
    }
    if (char === '(') {
      tokens.push({kind: 'symbol', value: char, upper: char, from: index, to: index + 1, depth});
      depth += 1;
      index += 1;
      continue;
    }
    if (char === ')') {
      depth = Math.max(0, depth - 1);
      tokens.push({kind: 'symbol', value: char, upper: char, from: index, to: index + 1, depth});
      index += 1;
      continue;
    }
    tokens.push({kind: 'symbol', value: char, upper: char, from: index, to: index + 1, depth});
    index += 1;
  }
  return {tokens, blocked};
};

const isIdentifier = (token: SqlToken | undefined): token is SqlToken => token?.kind === 'identifier';

const matchingParen = (tokens: SqlToken[], openIndex: number) => {
  const open = tokens[openIndex];
  if (open?.value !== '(') return -1;
  for (let index = openIndex + 1; index < tokens.length; index += 1) {
    if (tokens[index].value === ')' && tokens[index].depth === open.depth) return index;
  }
  return -1;
};

const scopeRange = (tokens: SqlToken[], cursor: number, depth: number): {from: number; to: number} => {
  if (depth === 0) return {from: 0, to: Number.MAX_SAFE_INTEGER};
  for (let index = tokens.length - 1; index >= 0; index -= 1) {
    const token = tokens[index];
    if (token.from >= cursor || token.value !== '(' || token.depth !== depth - 1) continue;
    const closeIndex = matchingParen(tokens, index);
    if (closeIndex < 0 || tokens[closeIndex].to >= cursor) {
      return {from: token.to, to: closeIndex < 0 ? Number.MAX_SAFE_INTEGER : tokens[closeIndex].from};
    }
  }
  return {from: 0, to: Number.MAX_SAFE_INTEGER};
};

const deriveSelectColumns = (tokens: SqlToken[], depth: number): string[] => {
  const selectIndex = tokens.findIndex(token => token.depth === depth && token.upper === 'SELECT');
  if (selectIndex < 0) return [];
  let end = tokens.length;
  for (let index = selectIndex + 1; index < tokens.length; index += 1) {
    if (tokens[index].depth === depth && tokens[index].upper === 'FROM') {
      end = index;
      break;
    }
  }
  const items: SqlToken[][] = [];
  let current: SqlToken[] = [];
  for (let index = selectIndex + 1; index < end; index += 1) {
    const token = tokens[index];
    if (token.depth === depth && token.value === ',') {
      if (current.length) items.push(current);
      current = [];
    } else {
      current.push(token);
    }
  }
  if (current.length) items.push(current);
  return [...new Set(items.map(item => {
    for (let index = item.length - 2; index >= 0; index -= 1) {
      if (item[index].depth === depth && item[index].upper === 'AS' && isIdentifier(item[index + 1])) {
        return item[index + 1].value;
      }
    }
    const visible = item.filter(token => token.depth >= depth);
    const last = visible[visible.length - 1];
    const previous = visible[visible.length - 2];
    if (!isIdentifier(last) || reservedAliasWords.has(last.upper)) return '';
    if (previous && previous.value !== '.' && previous.to < last.from) return last.value;
    const simplePath = visible.every(token => token.kind === 'identifier' || token.value === '.');
    return simplePath ? last.value : '';
  }).filter(Boolean))];
};

type ParsedCte = {name: string; columns: string[]};

const parseCtes = (tokens: SqlToken[], cursorDepth: number) => {
  const ctes = new Map<string, ParsedCte>();
  for (let withIndex = 0; withIndex < tokens.length; withIndex += 1) {
    const withToken = tokens[withIndex];
    if (withToken.upper !== 'WITH' || withToken.depth > cursorDepth) continue;
    let index = withIndex + 1;
    if (tokens[index]?.upper === 'RECURSIVE') index += 1;
    while (index < tokens.length && isIdentifier(tokens[index]) && tokens[index].depth === withToken.depth) {
      const name = tokens[index].value;
      index += 1;
      let explicitColumns: string[] = [];
      if (tokens[index]?.value === '(' && tokens[index].depth === withToken.depth) {
        const close = matchingParen(tokens, index);
        if (close < 0) break;
        explicitColumns = tokens.slice(index + 1, close)
          .filter(token => token.depth === withToken.depth + 1 && isIdentifier(token))
          .map(token => token.value);
        index = close + 1;
      }
      if (tokens[index]?.upper !== 'AS' || tokens[index + 1]?.value !== '(') break;
      const open = index + 1;
      const close = matchingParen(tokens, open);
      if (close < 0) break;
      const derived = deriveSelectColumns(tokens.slice(open + 1, close), withToken.depth + 1);
      ctes.set(name.toLowerCase(), {name, columns: explicitColumns.length ? explicitColumns : derived});
      index = close + 1;
      if (tokens[index]?.value !== ',' || tokens[index].depth !== withToken.depth) break;
      index += 1;
    }
  }
  return ctes;
};

const parseRelationAt = (
  tokens: SqlToken[],
  start: number,
  depth: number,
  ctes: Map<string, ParsedCte>,
): {binding?: SqlRelationBinding; end: number} => {
  let index = start;
  if (tokens[index]?.value === '(' && tokens[index].depth === depth) {
    const close = matchingParen(tokens, index);
    if (close < 0) return {end: index};
    let aliasIndex = close + 1;
    if (tokens[aliasIndex]?.upper === 'AS') aliasIndex += 1;
    if (!isIdentifier(tokens[aliasIndex]) || reservedAliasWords.has(tokens[aliasIndex].upper)) {
      return {end: close};
    }
    return {
      binding: {
        alias: tokens[aliasIndex].value,
        path: [],
        columns: deriveSelectColumns(tokens.slice(index + 1, close), depth + 1),
      },
      end: aliasIndex,
    };
  }
  if (!isIdentifier(tokens[index]) || tokens[index].depth !== depth) return {end: index};
  const path: string[] = [tokens[index].value];
  index += 1;
  while (tokens[index]?.value === '.' && isIdentifier(tokens[index + 1])
    && tokens[index].depth === depth && tokens[index + 1].depth === depth) {
    path.push(tokens[index + 1].value);
    index += 2;
  }
  let aliasIndex = index;
  if (tokens[aliasIndex]?.upper === 'AS') aliasIndex += 1;
  const aliasToken = isIdentifier(tokens[aliasIndex]) && !reservedAliasWords.has(tokens[aliasIndex].upper)
    ? tokens[aliasIndex]
    : undefined;
  const cteColumns = path.length === 1 ? ctes.get(path[0].toLowerCase())?.columns : undefined;
  return {
    binding: {
      alias: aliasToken?.value ?? path[path.length - 1],
      path,
      ...(cteColumns ? {columns: cteColumns} : {}),
    },
    end: aliasToken ? aliasIndex : index - 1,
  };
};

const parseBindings = (
  tokens: SqlToken[],
  cursor: number,
  cursorDepth: number,
  ctes: Map<string, ParsedCte>,
) => {
  const bindings = new Map<string, SqlRelationBinding>();
  const fromDepths = new Set<number>();
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (token.depth > cursorDepth) continue;
    if (token.depth < cursorDepth && token.from > cursor) continue;
    const range = scopeRange(tokens, cursor, token.depth);
    if (token.from < range.from || token.to > range.to) continue;
    if (clauseEnd.has(token.upper)) fromDepths.delete(token.depth);
    const startsRelation = token.upper === 'FROM' || token.upper === 'JOIN'
      || token.upper === 'UPDATE' || token.upper === 'INTO';
    const commaRelation = token.value === ',' && fromDepths.has(token.depth);
    if (!startsRelation && !commaRelation) continue;
    if (token.upper === 'FROM') fromDepths.add(token.depth);
    const parsed = parseRelationAt(tokens, index + 1, token.depth, ctes);
    if (parsed.binding) bindings.set(parsed.binding.alias.toLowerCase(), parsed.binding);
    index = Math.max(index, parsed.end);
  }
  return [...bindings.values()];
};

const cursorDepth = (tokens: SqlToken[], cursor: number) => {
  const before = tokens.filter(token => token.to <= cursor);
  const last = before[before.length - 1];
  if (!last) return 0;
  return last.value === '(' ? last.depth + 1 : last.depth;
};

const completionTarget = (sql: string, cursor: number, tokens: SqlToken[]) => {
  const containing = tokens.find(token => isIdentifier(token) && cursor > token.from && cursor <= token.to);
  let from = cursor;
  let prefix = '';
  if (containing) {
    from = containing.from;
    prefix = unquoteIdentifier(sql.slice(from, cursor));
  } else {
    const match = sql.slice(0, cursor).match(/[\p{L}\p{Nl}\p{N}\p{Mn}\p{Mc}_$]*$/u);
    prefix = match?.[0] ?? '';
    from = cursor - prefix.length;
  }
  const qualifier: string[] = [];
  let index = tokens.length - 1;
  while (index >= 0 && tokens[index].to > from) index -= 1;
  if (tokens[index]?.value === '.') {
    while (index >= 1 && tokens[index].value === '.' && isIdentifier(tokens[index - 1])) {
      qualifier.unshift(tokens[index - 1].value);
      index -= 2;
    }
  }
  return {from, prefix, qualifier};
};

const relationExpected = (tokens: SqlToken[], from: number, depth: number) => {
  let index = tokens.length - 1;
  while (index >= 0 && tokens[index].to > from) index -= 1;
  const previous = tokens[index];
  if (previous && ['FROM', 'JOIN', 'UPDATE', 'INTO', 'TABLE'].includes(previous.upper)) return true;
  if (previous?.value !== ',') return false;
  for (index -= 1; index >= 0; index -= 1) {
    const token = tokens[index];
    if (token.depth !== depth) continue;
    if (token.upper === 'FROM') return true;
    if (clauseEnd.has(token.upper)) return false;
  }
  return false;
};

/** Analyze only the statement containing the caret and derive its visible SQL names. */
export function analyzeSqlCompletion(document: string, position: number): SqlCompletionAnalysis {
  const statement = sqlStatementAt(document, position);
  const localCursor = Math.max(0, position - statement.from);
  const lexed = tokenizeSql(statement.text, localCursor);
  const target = completionTarget(statement.text, localCursor, lexed.tokens);
  const depth = cursorDepth(lexed.tokens, localCursor);
  const cteMap = parseCtes(lexed.tokens, depth);
  const bindings = parseBindings(lexed.tokens, localCursor, depth, cteMap);
  const range = scopeRange(lexed.tokens, localCursor, depth);
  const scopeTokens = lexed.tokens.filter(token => token.from >= range.from && token.to <= range.to);
  return {
    blocked: lexed.blocked,
    from: statement.from + target.from,
    prefix: target.prefix,
    qualifier: target.qualifier,
    expected: target.qualifier.length > 0
      ? 'qualified'
      : relationExpected(lexed.tokens, target.from, depth) ? 'relation' : 'expression',
    bindings,
    ctes: [...cteMap.values()],
    selectAliases: deriveSelectColumns(scopeTokens, depth),
  };
}

const equalName = (left: string | null | undefined, right: string | null | undefined) => (
  left?.localeCompare(right ?? '', undefined, {sensitivity: 'accent'}) === 0
  || left?.toLowerCase() === right?.toLowerCase()
);

const needsQuoting = (value: string) => !/^[a-z_][a-z0-9_$]*$/.test(value)
  || reservedIdentifiers.has(value.toUpperCase());

const quotedApply = (value: string, quote: string | null | undefined) => {
  if (!needsQuoting(value) || !quote) return value;
  const opening = quote.trim() || '"';
  const closing = opening === '[' ? ']' : opening;
  return `${opening}${value.split(closing).join(closing + closing)}${closing}`;
};

const pathNames = (relation: SqlCompletionRelation) => relation.path.map(segment => segment.name);

const preferredRelation = (
  relations: SqlCompletionRelation[],
  path: string[],
  catalog: SqlCompletionCatalog,
  defaultSchema?: string,
) => relations
  .filter(relation => {
    const names = pathNames(relation);
    return path.length <= names.length
      && path.every((name, index) => equalName(name, names[names.length - path.length + index]));
  })
  .sort((left, right) => {
    const preferredSchema = defaultSchema || catalog.currentSchema;
    const score = (relation: SqlCompletionRelation) => {
      const names = pathNames(relation);
      let value = 0;
      if (preferredSchema && names.some(name => equalName(name, preferredSchema))) value += 2;
      if (catalog.currentCatalog && names.some(name => equalName(name, catalog.currentCatalog))) value += 1;
      return value;
    };
    return score(right) - score(left);
  })[0];

const resolveSource = (
  path: string[],
  provider: SqlCompletionMetadataProvider,
): {source?: SqlCompletionDataSource; path: string[]; explicit: boolean} => {
  const explicit = provider.dataSources.find(source => equalName(source.code, path[0]));
  if (explicit) return {source: explicit, path: path.slice(1), explicit: true};
  return {
    source: provider.dataSources.find(source => equalName(source.code, provider.catalogCode)),
    path,
    explicit: false,
  };
};

const columnDetail = (column: SqlCompletionColumn, owner?: string) => {
  const size = column.size && column.size > 0
    ? `(${column.size}${column.scale != null && column.scale >= 0 ? `,${column.scale}` : ''})`
    : '';
  return [owner, `${column.typeName ?? ''}${size}`].filter(Boolean).join(' · ');
};

const columnSuggestions = (
  columns: readonly SqlCompletionColumn[] | readonly string[],
  quote: string | null | undefined,
  owner?: string,
  target?: SqlMetadataTarget,
): SqlCompletionSuggestion[] => columns.map(column => {
  const value = typeof column === 'string' ? column : column.name;
  return {
    label: value,
    apply: quotedApply(value, quote),
    type: 'field',
    detail: typeof column === 'string' ? owner : columnDetail(column, owner),
    info: typeof column === 'string' ? undefined : column.remarks ?? undefined,
    boost: 90,
    metadataTarget: target ? {
      dataSourceId: target.dataSourceId,
      path: [...target.path, {type: 'COLUMN', name: value}],
    } : undefined,
  };
});

const loadBindingColumns = async (
  binding: SqlRelationBinding,
  provider: SqlCompletionMetadataProvider,
) => {
  if (binding.columns) return {columns: binding.columns, quote: '"', target: undefined};
  const resolved = resolveSource(binding.path, provider);
  if (!resolved.source || resolved.path.length === 0) return null;
  const catalog = await provider.loadCatalog(resolved.source.id);
  const relation = preferredRelation(catalog.relations, resolved.path, catalog, provider.defaultSchema);
  if (!relation) return null;
  const structure = await provider.loadStructure(resolved.source.id, relation.path);
  return {
    columns: structure.columns ?? [],
    quote: catalog.identifierQuote,
    target: {dataSourceId: resolved.source.id, path: relation.path},
  };
};

const namespaceSuggestions = async (
  qualifier: string[],
  provider: SqlCompletionMetadataProvider,
): Promise<SqlCompletionSuggestion[]> => {
  const resolved = resolveSource(qualifier, provider);
  if (!resolved.source) return [];
  const catalog = await provider.loadCatalog(resolved.source.id);
  const seen = new Set<string>();
  const suggestions: SqlCompletionSuggestion[] = [];
  const add = (
    name: string,
    type: SqlCompletionSuggestion['type'],
    detail?: string,
    info?: string | null,
    path?: SqlCompletionPathSegment[],
  ) => {
    const key = `${type}:${name.toLowerCase()}`;
    if (seen.has(key)) return;
    seen.add(key);
    suggestions.push({
      label: name,
      apply: quotedApply(name, catalog.identifierQuote),
      type,
      detail,
      info: info ?? undefined,
      boost: type === 'table' || type === 'view' ? 80 : 65,
      metadataTarget: path ? {dataSourceId: resolved.source!.id, path} : undefined,
    });
  };

  for (const relation of catalog.relations) {
    const names = pathNames(relation);
    if (resolved.explicit && resolved.path.length === 0) {
      const segment = relation.path[0];
      if (segment) add(segment.name, 'namespace', segment.type, undefined, relation.path.slice(0, 1));
      continue;
    }
    if (resolved.path.length > 0) {
      for (let start = 0; start + resolved.path.length < names.length; start += 1) {
        if (!resolved.path.every((name, index) => equalName(name, names[start + index]))) continue;
        const segment = relation.path[start + resolved.path.length];
        const last = start + resolved.path.length === names.length - 1;
        add(segment.name, last ? relation.type.toLowerCase() as 'table' | 'view' : 'namespace',
          last ? relation.type : segment.type, last ? relation.remarks : undefined,
          relation.path.slice(0, start + resolved.path.length + 1));
      }
      continue;
    }

    const relationSegment = relation.path[relation.path.length - 1];
    const schemaSegment = relation.path.find(segment => segment.type === 'SCHEMA');
    const catalogSegment = relation.path.find(segment => segment.type === 'DATABASE' || segment.type === 'CATALOG');
    const effectiveSchema = provider.defaultSchema || catalog.currentSchema;
    const inDefaultNamespace = (!schemaSegment || !effectiveSchema || equalName(schemaSegment.name, effectiveSchema))
      && (!catalogSegment || !catalog.currentCatalog || equalName(catalogSegment.name, catalog.currentCatalog));
    if (inDefaultNamespace) {
      add(relationSegment.name, relation.type.toLowerCase() as 'table' | 'view', relation.type, relation.remarks,
        relation.path);
    }
    if (schemaSegment) {
      const schemaIndex = relation.path.indexOf(schemaSegment);
      add(schemaSegment.name, 'namespace', 'SCHEMA', undefined, relation.path.slice(0, schemaIndex + 1));
    }
    if (catalogSegment) {
      const catalogIndex = relation.path.indexOf(catalogSegment);
      add(catalogSegment.name, 'namespace', catalogSegment.type, undefined,
        relation.path.slice(0, catalogIndex + 1));
    }
  }
  return suggestions;
};

const deduplicateSuggestions = (suggestions: SqlCompletionSuggestion[]) => {
  const result = new Map<string, SqlCompletionSuggestion>();
  for (const suggestion of suggestions) {
    const key = `${suggestion.type}:${suggestion.label.toLowerCase()}`;
    const current = result.get(key);
    if (!current || (suggestion.boost ?? 0) > (current.boost ?? 0)) result.set(key, suggestion);
  }
  return [...result.values()];
};

/** Resolve permission-scoped metadata suggestions for one analyzed caret position. */
export async function completeSqlMetadata(
  analysis: SqlCompletionAnalysis,
  provider: SqlCompletionMetadataProvider,
): Promise<SqlCompletionSuggestion[]> {
  if (analysis.blocked) return [];
  const suggestions: SqlCompletionSuggestion[] = [];
  if (analysis.qualifier.length > 0) {
    const alias = analysis.bindings.find(binding => equalName(binding.alias, analysis.qualifier[0]));
    if (alias && analysis.qualifier.length === 1) {
      const resolved = await loadBindingColumns(alias, provider);
      if (resolved) {
        suggestions.push(...columnSuggestions(resolved.columns, resolved.quote, alias.alias, resolved.target));
      }
      return deduplicateSuggestions(suggestions);
    }
    const cte = analysis.ctes.find(item => equalName(item.name, analysis.qualifier[0]));
    if (cte && analysis.qualifier.length === 1) {
      return columnSuggestions(cte.columns, '"', cte.name);
    }
    const sourcePath = resolveSource(analysis.qualifier, provider);
    if (sourcePath.source && sourcePath.path.length > 0) {
      const catalog = await provider.loadCatalog(sourcePath.source.id);
      const relation = preferredRelation(catalog.relations, sourcePath.path, catalog, provider.defaultSchema);
      if (relation && pathNames(relation).length >= sourcePath.path.length) {
        const relationNames = pathNames(relation);
        const exactTail = sourcePath.path.every((name, index) => equalName(
          name,
          relationNames[relationNames.length - sourcePath.path.length + index],
        ));
        if (exactTail && equalName(sourcePath.path[sourcePath.path.length - 1], relationNames[relationNames.length - 1])) {
          const structure = await provider.loadStructure(sourcePath.source.id, relation.path);
          return columnSuggestions(
            structure.columns ?? [],
            catalog.identifierQuote,
            relationNames[relationNames.length - 1],
            {dataSourceId: sourcePath.source.id, path: relation.path},
          );
        }
      }
    }
    return namespaceSuggestions(analysis.qualifier, provider);
  }

  if (analysis.expected === 'relation') {
    suggestions.push(...provider.dataSources.map(source => ({
      label: source.code,
      apply: quotedApply(source.code, '"'),
      type: 'namespace' as const,
      detail: source.name || source.databaseProductName || 'DATA SOURCE',
      boost: 70,
      metadataTarget: {dataSourceId: source.id, path: []},
    })));
    suggestions.push(...analysis.ctes.map(cte => ({
      label: cte.name,
      apply: quotedApply(cte.name, '"'),
      type: 'table' as const,
      detail: 'CTE',
      boost: 100,
    })));
    suggestions.push(...await namespaceSuggestions([], provider));
    return deduplicateSuggestions(suggestions);
  }

  suggestions.push(...analysis.bindings.map(binding => ({
    label: binding.alias,
    apply: quotedApply(binding.alias, '"'),
    type: 'variable' as const,
    detail: binding.columns ? 'DERIVED TABLE' : binding.path.join('.'),
    boost: 100,
  })));
  suggestions.push(...analysis.selectAliases.map(alias => ({
    label: alias,
    apply: quotedApply(alias, '"'),
    type: 'variable' as const,
    detail: 'SELECT ALIAS',
    boost: 95,
  })));
  const columnGroups = await Promise.allSettled(analysis.bindings.slice(0, 12).map(async binding => {
    const resolved = await loadBindingColumns(binding, provider);
    return resolved ? columnSuggestions(resolved.columns, resolved.quote, binding.alias, resolved.target) : [];
  }));
  columnGroups.forEach(result => {
    if (result.status === 'fulfilled') suggestions.push(...result.value);
  });
  return deduplicateSuggestions(suggestions);
}

const identifierRangeAt = (document: string, position: number) => {
  const safe = Math.max(0, Math.min(position, document.length));
  let probe = safe < document.length && identifierPart.test(document[safe]) ? safe : safe - 1;
  if (probe < 0) return null;
  const lineStart = document.lastIndexOf('\n', probe) + 1;
  const lineEndIndex = document.indexOf('\n', probe);
  const lineEnd = lineEndIndex < 0 ? document.length : lineEndIndex;
  for (const [opening, closing] of [['"', '"'], ['`', '`'], ['[', ']']] as const) {
    const open = document.lastIndexOf(opening, probe);
    const close = document.indexOf(closing, probe + 1);
    if (open >= lineStart && open <= probe && (close < 0 || close <= lineEnd)) {
      const to = close < 0 ? lineEnd : close + 1;
      return {from: open, to, value: unquoteIdentifier(document.slice(open, to))};
    }
  }
  if (!identifierPart.test(document[probe])) return null;
  let from = probe;
  let to = probe + 1;
  while (from > lineStart && identifierPart.test(document[from - 1])) from -= 1;
  while (to < lineEnd && identifierPart.test(document[to])) to += 1;
  return {from, to, value: document.slice(from, to)};
};

/** Resolve the metadata object under a hover or navigation gesture. */
export async function resolveSqlSymbol(
  document: string,
  position: number,
  provider: SqlCompletionMetadataProvider,
): Promise<SqlResolvedSymbol | null> {
  const range = identifierRangeAt(document, position);
  if (!range) return null;
  const analysis = analyzeSqlCompletion(document, range.to);
  if (analysis.blocked) return null;
  const suggestions = await completeSqlMetadata(analysis, provider);
  const suggestion = suggestions.find(item => equalName(item.label, range.value)
    && (item.metadataTarget || item.info || item.detail));
  return suggestion ? {from: range.from, to: range.to, suggestion} : null;
}
