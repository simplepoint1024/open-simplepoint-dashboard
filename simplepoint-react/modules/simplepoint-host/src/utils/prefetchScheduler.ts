/** Limit speculative downloads; route navigation never waits for this queue. */
export function createPrefetchScheduler(concurrency = 2, delay = 180) {
  const pending = new Map<string, {task: () => Promise<unknown>; timer?: ReturnType<typeof setTimeout>}>();
  const active = new Set<string>();

  const drain = () => {
    for (const [key, entry] of pending) {
      if (active.size >= concurrency) break;
      if (entry.timer !== undefined) continue;
      pending.delete(key);
      active.add(key);
      void Promise.resolve().then(entry.task).catch(() => {
        // Speculation is optional. The route loader owns user-facing errors.
      }).finally(() => {
        active.delete(key);
        drain();
      });
    }
  };

  return {
    schedule(key: string, task: () => Promise<unknown>) {
      if (pending.has(key) || active.has(key)) return;
      const entry = {task, timer: undefined as ReturnType<typeof setTimeout> | undefined};
      entry.timer = setTimeout(() => {
        entry.timer = undefined;
        drain();
      }, delay);
      pending.set(key, entry);
    },
    cancel(key: string) {
      clearTimeout(pending.get(key)?.timer);
      pending.delete(key);
    },
  };
}

export function permitsSpeculation(connection?: {saveData?: boolean; effectiveType?: string}) {
  return !connection?.saveData && !['slow-2g', '2g', '3g'].includes(connection?.effectiveType ?? '');
}
