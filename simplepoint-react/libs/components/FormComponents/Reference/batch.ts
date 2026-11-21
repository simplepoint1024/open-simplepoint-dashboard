import type {ReferenceOption, ReferenceTransport} from './source.ts';

type Pending = {
  ids: string[];
  signal: AbortSignal;
  resolve: (rows: ReferenceOption[]) => void;
  reject: (error: unknown) => void;
  aborted: boolean;
  abort: () => void;
  cancelBatch?: () => void;
};
const abortError = (signal: AbortSignal) => signal.reason ?? new DOMException('Aborted', 'AbortError');

/** One instance per relation/authorized runtime. Coalesce detail-row hydration without a global cache. */
export function batchReferenceLookups(fetch: ReferenceTransport['resolve']): ReferenceTransport['resolve'] {
  let queue: Pending[] = [];
  async function flush() {
    const pending = queue;
    queue = [];
    const active = pending.filter(item => !item.aborted);
    if (!active.length) return;
    const controller = new AbortController();
    for (const item of active) item.cancelBatch = () => {
      if (active.every(request => request.aborted)) controller.abort();
    };
    const ids = [...new Set(active.flatMap(item => item.ids))];
    const rows = new Map<string, ReferenceOption>();
    try {
      for (let start = 0; start < ids.length; start += 100) {
        controller.signal.throwIfAborted();
        const batch = ids.slice(start, start + 100);
        const result = await fetch(batch, controller.signal);
        controller.signal.throwIfAborted();
        for (const row of result) if (batch.includes(row.id)) rows.set(row.id, row);
      }
      for (const item of active) if (!item.aborted) item.resolve(item.ids.filter(id => rows.has(id)).map(id => rows.get(id)!));
    } catch (failure) {
      for (const item of active) if (!item.aborted) item.reject(failure);
    } finally {
      for (const item of active) item.signal.removeEventListener('abort', item.abort);
    }
  }
  return (ids, signal) => {
    if (signal.aborted) return Promise.reject(abortError(signal));
    return new Promise((resolve, reject) => {
      const item: Pending = {ids: [...new Set(ids)], signal, resolve, reject, aborted: false, abort: () => {}};
      item.abort = () => {item.aborted = true; reject(abortError(signal)); item.cancelBatch?.();};
      signal.addEventListener('abort', item.abort, {once: true});
      queue.push(item);
      if (queue.length === 1) queueMicrotask(() => void flush());
    });
  };
}
