export function createLoadingState(delay = 200, hintDelay = 1200) {
  const tasks = new Set<symbol>();
  const listeners = new Set<() => void>();
  let snapshot = {busy: false, visible: false, slow: false};
  let timer: ReturnType<typeof setTimeout> | undefined;
  let hintTimer: ReturnType<typeof setTimeout> | undefined;
  const publish = (next: typeof snapshot) => {
    snapshot = next;
    listeners.forEach(listener => listener());
  };
  return {
    subscribe(listener: () => void) {
      listeners.add(listener);
      return () => {listeners.delete(listener);};
    },
    getSnapshot: () => snapshot,
    register() {
      const task = Symbol();
      tasks.add(task);
      if (!snapshot.busy) {
        publish({busy: true, visible: false, slow: false});
        timer = setTimeout(() => publish({...snapshot, visible: true}), delay);
        hintTimer = setTimeout(() => publish({...snapshot, slow: true}), hintDelay);
      }
      return () => {
        tasks.delete(task);
        // MF fallback cleanup and the page's data-loader mount share a commit.
        // Coalesce them so the same progress bar/timer survives that handoff.
        queueMicrotask(() => {
          if (tasks.size || !snapshot.busy) return;
          clearTimeout(timer);
          clearTimeout(hintTimer);
          publish({busy: false, visible: false, slow: false});
        });
      };
    },
  };
}
