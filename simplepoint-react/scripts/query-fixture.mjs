import {compileLocalQuery, localQuerySchema} from '../libs/components/DataTable/localQuery.ts';

// Isolated browser transport simulation only. SQL behavior is tested against the real repositories.
export function fixtureSchema(fields) {
  const schema = localQuerySchema(fields);
  schema['x-query'].maxPageSize = 100;
  return {schema, buttons: []};
}

export function fixturePage(rows, fields, url, fixed = []) {
  const filters = Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort', ...fixed].includes(key)));
  const query = compileLocalQuery(fields, {filters, sort: url.searchParams.getAll('sort')});
  const filtered = rows.filter(query.matches).sort(query.compare);
  const page = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20);
  if (!Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1 || size > 100) throw Error('Invalid fixture query page');
  return {content: filtered.slice(page * size, (page + 1) * size), page, size, totalElements: filtered.length};
}
