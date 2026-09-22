import {useCallback, useEffect, useRef, useState} from 'react';
import {useQueryClient} from '@tanstack/react-query';
import {get, put, useData} from '@simplepoint/shared/api/methods';
import {getQueryScopeSnapshot, scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {parsePreferences, type HomePreferences} from './preferences';

export function useHomePreferences(userId?: string) {
  const scope = useQueryScope();
  const key = `home.v1.${scope[0] || ''}`;
  const queryKey = ['home-preferences', userId, key];
  const url = `/common/users/preferences?key=${encodeURIComponent(key)}`;
  const client = useQueryClient();
  const request = useRef<AbortController | null>(null);
  const [saving, setSaving] = useState(false);
  const query = useData<HomePreferences>(queryKey, async ({signal}) => parsePreferences(await get(url, undefined, {signal})), {
    enabled: Boolean(userId && scope[0]), staleTime: 300_000, retry: 1,
  });
  useEffect(() => () => { request.current?.abort(); }, [userId, key]);
  const save = useCallback(async (value: HomePreferences) => {
    if (!userId || !scope[0] || request.current || JSON.stringify(scope) !== getQueryScopeSnapshot()) return false;
    const parsed = parsePreferences(value);
    const controller = new AbortController();
    request.current = controller;
    setSaving(true);
    try {
      await client.cancelQueries({queryKey: scopedQueryKey(scope, ['home-preferences', userId, key])});
      await put(url, {value: JSON.stringify(parsed)}, {signal: controller.signal});
      if (controller.signal.aborted || JSON.stringify(scope) !== getQueryScopeSnapshot()) return false;
      client.setQueryData(scopedQueryKey(scope, ['home-preferences', userId, key]), parsed);
      return true;
    } finally {
      request.current = null;
      if (!controller.signal.aborted) setSaving(false);
    }
  }, [client, userId, key, url, scope]);
  return {...query, save, saving};
}
