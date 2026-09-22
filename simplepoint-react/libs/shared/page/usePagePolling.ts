import {useEffect, useMemo} from 'react';
import {usePageActive} from './hooks';
import {createSerialPoller} from './polling';

/** Wait after completion, so slow external connections never accumulate overlapping polls. */
export function usePagePolling(task: () => Promise<unknown>, enabled: boolean, intervalMs: number) {
  const active = usePageActive();
  const start = useMemo(createSerialPoller, []);
  useEffect(() => {
    if (!active || !enabled) return;
    return start(task, intervalMs, error => console.warn('[Polling] Background refresh failed', error));
  }, [active, enabled, intervalMs, start, task]);
}
