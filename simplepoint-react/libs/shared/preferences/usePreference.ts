import {useCallback, useEffect, useRef, useState, useSyncExternalStore} from 'react';
import type {Dispatch, SetStateAction} from 'react';
import {get, put} from '../api/methods';
import {getQueryScopeSnapshot} from '../api/queryScope';
import {useQueryScope} from '../hooks/useQueryScope';
import {createPreferenceSession} from './session';

export function preferenceUser() {
  try {
    const user = JSON.parse(sessionStorage.getItem('sp.userinfo') ?? '{}');
    return String(user.sub ?? user.id ?? user.username ?? user.preferred_username ?? 'anonymous');
  } catch {return 'anonymous';}
}
export {tablePreferenceIdentity} from './identity';

const subscribeUser = (notify: () => void) => {
  window.addEventListener('sp-userinfo-changed', notify); window.addEventListener('storage', notify);
  return () => {window.removeEventListener('sp-userinfo-changed', notify); window.removeEventListener('storage', notify);};
};

/** Shared account-synced storage; legacy keys remain readable, defaults are never saved on mount. */
export function usePreference<T>(key: string | undefined, initial: T, normalize: (value: unknown) => T,
  legacyLocalKey?: string) {
  const scope = JSON.stringify(useQueryScope());
  const user = useSyncExternalStore(subscribeUser, preferenceUser, () => 'anonymous');
  const owner = JSON.stringify([user, scope, key]);
  const localKey = key ? `sp.preference.v1.${user}.${key}` : undefined;
  const [state, setState] = useState<{owner: string; value: T}>({owner, value: initial});
  const [failed, setFailed] = useState(false);
  const session = useRef<ReturnType<typeof createPreferenceSession<T>> | undefined>(undefined);
  const latest = useRef({owner, initial, normalize}); latest.current = {owner, initial, normalize};
  const valueRef = useRef(initial);
  useEffect(() => {
    const abort = new AbortController();
    const current = () => !abort.signal.aborted && latest.current.owner === owner
      && getQueryScopeSnapshot() === scope && preferenceUser() === user;
    let value = latest.current.initial, dirty = false;
    try {
      const stored = localKey ? localStorage.getItem(localKey) : null;
      if (stored) {
        const cache = JSON.parse(stored);
        if (cache.version === 1) {value = latest.current.normalize(cache.value); dirty = cache.dirty === true;}
      } else if (legacyLocalKey) {
        const legacy = localStorage.getItem(legacyLocalKey);
        if (legacy) value = latest.current.normalize(JSON.parse(legacy));
      }
    } catch { /* Invalid browser preferences fall back to the current schema. */ }
    valueRef.current = value; setState({owner, value}); setFailed(false);
    const target = async () => {
      let serverKey = key!;
      if (serverKey.length > 190) {
        const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(serverKey));
        serverKey = 'sp.preference.v1.' + Array.from(new Uint8Array(hash), b => b.toString(16).padStart(2, '0')).join('');
      }
      if (!current()) throw new Error('Preference owner changed');
      return `/common/users/preferences?key=${encodeURIComponent(serverKey)}`;
    };
    const next = createPreferenceSession<T>({initial: value, dirty, current,
      load: async () => {
        if (!key) return undefined;
        const raw = await get<string | undefined>(await target(), undefined, {signal: abort.signal, notifyOnError: false});
        return raw ? latest.current.normalize(JSON.parse(raw)) : undefined;
      },
      save: async next => {
        if (key) await put(await target(), {value: JSON.stringify(next)}, {signal: abort.signal, notifyOnError: false});
      },
      change: (next, pending) => {
        valueRef.current = next; setState({owner, value: next});
        if (localKey) try {localStorage.setItem(localKey, JSON.stringify({version: 1, value: next, dirty: pending}));} catch { /* Account sync still works without browser storage. */ }
      }, failure: setFailed});
    session.current = next; void next.load();
    return () => {next.dispose(); abort.abort(); if (session.current === next) session.current = undefined;};
  }, [owner, localKey, legacyLocalKey]);
  const setValue: Dispatch<SetStateAction<T>> = useCallback(next => {
    if (latest.current.owner !== owner) return;
    session.current?.update(typeof next === 'function' ? (next as (value: T) => T)(valueRef.current) : next);
  }, [owner]);
  return {value: state.owner === owner ? state.value : initial, setValue, failed,
    retry: () => void session.current?.retry(), reset: () => setValue(latest.current.initial)};
}
