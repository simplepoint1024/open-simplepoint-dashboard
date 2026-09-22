import {setTimeout as delay} from 'node:timers/promises';
import {compileLocalQuery, localQuerySchema} from '../libs/components/DataTable/localQuery.ts';

/** Isolated server-paged assignment fixture; never connects to production APIs. */
export function createAssignmentFixture() {
  const fields = {name: {type: 'string'}, code: {type: 'string'}, description: {type: 'string'}};
  const rows = Array.from({length: 240}, (_, i) => ({id: `app-${i}`, code: `app-${String(i).padStart(3, '0')}`,
    name: `分配应用 ${String(i).padStart(3, '0')}`, description: `fixture-${i}`}));
  const assigned = new Set(rows.slice(0, 120).map(row => row.code));
  let failNextMove = false;
  let moves = 0;
  return {
    assigned,
    get moves() {return moves;},
    failMove() {failNextMove = true;},
    async handle(req, url, respond, expectedFailure) {
      const endpoint = '/common/platform/applications/items';
      if (url.pathname === `${endpoint}/schema`) {
        return respond({schema: localQuerySchema(fields), buttons: []}), true;
      }
      if (url.pathname === endpoint) {
        const page = Number(url.searchParams.get('page') ?? 0);
        const size = Number(url.searchParams.get('size') ?? 20);
        if (size < 1 || size > 200) return respond({message: 'Page size exceeded'}, 400), true;
        const filters = Object.fromEntries([...url.searchParams].filter(([key]) => !['page', 'size', 'sort', 'packageCode', 'selected'].includes(key)));
        const query = compileLocalQuery(fields, {filters, sort: url.searchParams.getAll('sort')});
        const selected = url.searchParams.get('selected') === 'true';
        const result = rows.filter(row => assigned.has(row.code) === selected && query.matches(row)).sort(query.compare);
        return respond({content: result.slice(page * size, (page + 1) * size),
          page: {number: page, size, totalElements: result.length, totalPages: Math.ceil(result.length / size)}}), true;
      }
      if (['/common/platform/packages/authorize', '/common/platform/packages/unauthorized'].includes(url.pathname)) {
        moves++;
        const chunks = []; for await (const chunk of req) chunks.push(chunk);
        const body = JSON.parse(Buffer.concat(chunks).toString());
        await delay(250);
        if (failNextMove) {
          failNextMove = false;
          expectedFailure(503);
          return respond({message: '分配失败（测试）'}, 503), true;
        }
        for (const key of body.applicationCodes ?? []) {
          if (url.pathname.endsWith('/authorize')) assigned.add(key); else assigned.delete(key);
        }
        return respond([]), true;
      }
      return false;
    },
  };
}
