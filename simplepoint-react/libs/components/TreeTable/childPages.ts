export type ChildPage<T> = {content: T[]; hasNext: boolean};
export type ChildState<T> = {rows: T[]; page: number; hasNext: boolean; loading: boolean; failed: boolean};

/** One request per parent; reset aborts and fences every response from the old query scope. */
export function createChildPages<T>(load: (record: T, page: number, signal: AbortSignal) => Promise<ChildPage<T>>,
  keyOf: (row: T) => string | number | undefined,
  changed: (states: Map<string | number, ChildState<T>>) => void) {
  const states = new Map<string | number, ChildState<T>>();
  const pending = new Map<string | number, AbortController>();
  const publish = () => changed(new Map(states));
  return {
    cancelPending() {
      for (const [key, controller] of pending) {
        controller.abort();
        const state = states.get(key);
        if (state) states.set(key, {...state, loading: false});
      }
      pending.clear(); publish();
    },
    reset(notify = true) {
      for (const controller of pending.values()) controller.abort();
      pending.clear(); states.clear();
      if (notify) publish();
    },
    async next(record: T) {
      const key = keyOf(record);
      if (key === undefined || pending.has(key)) return;
      const previous = states.get(key) ?? {rows: [], page: 0, hasNext: true, loading: false, failed: false};
      if (!previous.hasNext) return;
      const controller = new AbortController();
      pending.set(key, controller);
      states.set(key, {...previous, loading: true, failed: false}); publish();
      try {
        const result = await load(record, previous.page, controller.signal);
        if (pending.get(key) !== controller) return;
        const unique = new Map(previous.rows.map(row => [keyOf(row), row]));
        for (const row of result.content) unique.set(keyOf(row), row);
        states.set(key, {rows: [...unique.values()], page: previous.page + 1,
          hasNext: result.hasNext, loading: false, failed: false});
      } catch {
        if (pending.get(key) !== controller) return;
        states.set(key, {...previous, loading: false, failed: true});
      } finally {
        if (pending.get(key) === controller) {
          pending.delete(key); publish();
        }
      }
    },
  };
}
