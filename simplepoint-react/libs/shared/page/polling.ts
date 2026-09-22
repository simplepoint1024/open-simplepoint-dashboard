/** One in-flight read across stop/start cycles, including fast tab switches. */
export function createSerialPoller() {
  let inFlight: Promise<unknown> | undefined;
  return (task: () => Promise<unknown>, intervalMs: number, onError: (error: unknown) => void) => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      const current = inFlight ?? Promise.resolve().then(task).catch(onError);
      inFlight = current;
      try {
        await current;
      } finally {
        if (inFlight === current) inFlight = undefined;
        if (!stopped) timer = setTimeout(tick, intervalMs);
      }
    };
    timer = setTimeout(tick, intervalMs);
    return () => { stopped = true; clearTimeout(timer); };
  };
}
