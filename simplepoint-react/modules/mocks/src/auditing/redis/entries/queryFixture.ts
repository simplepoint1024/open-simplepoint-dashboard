import schema from '../../../../../../scripts/redis-schema.json' with {type: 'json'};
import {compileLocalQuery, type LocalFields} from '../../../../../../libs/components/DataTable/localQuery.ts';

export {schema as redisSchema};
const fields: LocalFields = Object.fromEntries(Object.entries(schema.schema['x-query'].fields).map(([name, field]) => [name,
  {type: field.javaType === 'Boolean' ? 'boolean' : field.javaType === 'Long' ? 'integer'
    : field.javaType === 'Instant' ? 'date-time' : 'string'}]));

/** Redis glob operates on bytes, is case-sensitive and treats backslash as an escape. */
export function matchesRedisGlob(pattern: string, key: string): boolean {
  const encoder = new TextEncoder(), p = encoder.encode(pattern), k = encoder.encode(key);
  const memo = new Map<string, boolean>();
  const match = (a: number, b: number): boolean => {
    const id = `${a}:${b}`;
    if (memo.has(id)) return memo.get(id)!;
    let result: boolean;
    if (a === p.length) result = b === k.length;
    else if (p[a] === 42) result = match(a + 1, b) || b < k.length && match(a, b + 1);
    else if (b === k.length) result = false;
    else if (p[a] === 63) result = match(a + 1, b + 1);
    else if (p[a] === 91) {
      let c = a + 1, accepted = false;
      const inverse = p[c] === 94;
      if (inverse) c++;
      while (c < p.length && p[c] !== 93) {
        if (p[c] === 92 && c + 1 < p.length) accepted = p[++c] === k[b] || accepted;
        else if (c + 2 < p.length && p[c + 1] === 45) {
          accepted = k[b] >= Math.min(p[c], p[c + 2]) && k[b] <= Math.max(p[c], p[c + 2]) || accepted;
          c += 2;
        } else accepted = p[c] === k[b] || accepted;
        c++;
      }
      result = (inverse ? !accepted : accepted) && match(Math.min(c + 1, p.length), b + 1);
    } else {
      const c = p[a] === 92 && a + 1 < p.length ? a + 1 : a;
      result = p[c] === k[b] && match(c + 1, b + 1);
    }
    memo.set(id, result);
    return result;
  };
  return match(0, 0);
}

type Row = Record<string, unknown> & {key: string};
type Status = 'READY' | 'BUILDING' | 'EXPIRED' | 'BUSY' | 'FAILED' | 'TIMED_OUT' | 'LIMIT_EXCEEDED';

/** Development/browser fixture only. Production queries are evaluated by RedisEntryQueryService. */
export function createRedisSnapshotFixture(source: () => Row[]) {
  let sequence = 0;
  const snapshots = new Map<string, {id: string; rows: Row[]; created: number}>();
  let forced: Status | undefined;
  return {
    get builds() {return sequence;},
    status(status?: Status) {forced = status;},
    invalidate() {snapshots.clear();},
    query(params: URLSearchParams) {
      const page = Number(params.get('page') ?? 0), size = Number(params.get('size') ?? 20);
      if (!Number.isSafeInteger(page) || page < 0 || !Number.isSafeInteger(size) || size < 1 || size > 100) {
        throw Error('Invalid bounded page');
      }
      const compiled = compileLocalQuery(fields, {
        filters: Object.fromEntries([...params].filter(([key]) => !['page', 'size', 'sort', 'scanPattern', 'snapshotId', 'refresh'].includes(key))),
        sort: params.getAll('sort').length ? params.getAll('sort') : ['key,asc'],
      });
      const pattern = params.get('scanPattern') || '*', pin = params.get('snapshotId');
      let snapshot = snapshots.get(pattern);
      let status: Status = forced ?? 'READY';
      if (status !== 'BUSY' && status !== 'EXPIRED') {
        if (params.get('refresh') === 'true' && (!snapshot || !pin || snapshot.id === pin)) snapshot = undefined;
        else if (pin && snapshot?.id !== pin) status = 'EXPIRED';
        if (!snapshot && status !== 'EXPIRED') {
          snapshot = {id: `redis-fixture-${++sequence}`, rows: structuredClone(source().filter(row => matchesRedisGlob(pattern, row.key))), created: Date.now()};
          snapshots.set(pattern, snapshot);
        }
      }
      const rows = status === 'READY' ? snapshot!.rows.filter(compiled.matches).sort(compiled.compare) : [];
      return {status, snapshotId: status === 'BUSY' ? null : snapshot?.id ?? pin, pattern,
        observedKeys: snapshot?.rows.length ?? 0, startedAt: snapshot ? new Date(snapshot.created).toISOString() : null,
        completedAt: status === 'READY' ? new Date(snapshot!.created).toISOString() : null,
        expiresAt: snapshot ? new Date(snapshot.created + 300_000).toISOString() : null,
        message: ['READY', 'BUILDING', 'BUSY'].includes(status) ? null : '扫描结果不可用，请重试。',
        page: status === 'READY' ? {content: rows.slice(page * size, (page + 1) * size),
          page: {number: page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size)}} : null};
    },
  };
}
