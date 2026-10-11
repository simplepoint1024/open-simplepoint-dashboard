import {useCallback, useLayoutEffect, useRef, useState} from 'react';
import type {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageActive} from '@simplepoint/shared/page/hooks';

type Entry = {request: AbortController; promise: Promise<void>};

/** Channel-owned tree reads: one request per branch, no late commits after paging/hiding/scope changes. */
export function useOwnedReads(operation: ReturnType<typeof useOperationScope>, enabled: boolean) {
  const active = usePageActive();
  const entries = useRef(new Map<string, Entry>());
  const [loading, setLoading] = useState<string[]>([]);
  const [errors, setErrors] = useState<Record<string, unknown>>({});
  const cancel = useCallback((prefix = '') => {
    for (const [key, entry] of entries.current) {
      if (!key.startsWith(prefix)) continue;
      entries.current.delete(key); entry.request.abort();
    }
    setLoading([...entries.current.keys()]);
  }, []);
  const clearErrors = useCallback((prefix = '') => setErrors(previous => Object.fromEntries(
    Object.entries(previous).filter(([key]) => !key.startsWith(prefix)))), []);
  useLayoutEffect(() => {
    if (!enabled || !active) cancel();
    return () => cancel();
  }, [enabled, active, operation.current, cancel]);
  const isBusy = useCallback(() => entries.current.size > 0, []);
  const run = useCallback(<T,>(channel: string, fetch: (signal: AbortSignal) => Promise<T>,
    commit: (value: T) => void): Promise<void> => {
    if (!enabled || !active || !operation.current() || !operation.isSelected() || operation.isBusy()) return Promise.resolve();
    const pending = entries.current.get(channel);
    if (pending) return pending.promise;
    const request = new AbortController();
    const current = () => operation.current(request.signal) && operation.isSelected()
      && entries.current.get(channel)?.request === request;
    // Defer the fetch by one microtask so the entry is installed before even a synchronous result.
    const promise = Promise.resolve().then(() => fetch(request.signal)).then(value => {
      if (!current()) return;
      commit(value);
      setErrors(previous => {const next = {...previous}; delete next[channel]; return next;});
    }).catch(error => {if (current()) setErrors(previous => ({...previous, [channel]: error}));})
      .finally(() => {
        if (entries.current.get(channel)?.request !== request) return;
        entries.current.delete(channel);
        if (operation.current()) setLoading([...entries.current.keys()]);
      });
    entries.current.set(channel, {request, promise});
    setLoading([...entries.current.keys()]);
    return promise;
  }, [enabled, active, operation.current, operation.isSelected, operation.isBusy]);
  return {run, cancel, clearErrors, isBusy, loading, errors, active};
}
