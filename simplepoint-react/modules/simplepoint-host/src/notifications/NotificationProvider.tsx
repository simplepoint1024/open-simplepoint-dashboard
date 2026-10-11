import {createContext, useCallback, useContext, useEffect, useLayoutEffect, useRef, useState, type ReactNode} from 'react';
import {App} from 'antd';
import {useQueryClient} from '@tanstack/react-query';
import {get, put, useData} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {getQueryScopeSnapshot, scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {normalizePage, type Page} from '@simplepoint/shared/types/request';
import {useI18n} from '@/layouts/i18n/useI18n';
import {consumeNotificationStream} from './stream';
import type {InboxNotification} from './models';

const BASE = '/common/notifications';
type NotificationContextValue = {
  enabled: boolean; unread: number; open: boolean; busy: boolean;
  setOpen: (open: boolean) => void;
  markRead: (id?: string) => Promise<boolean>;
};
const NotificationContext = createContext<NotificationContextValue | null>(null);

/** One stream per authenticated workspace, shared by the home feed and bell. */
export function NotificationProvider({enabled, children}: {enabled: boolean; children: ReactNode}) {
  const {t, ensure, locale} = useI18n();
  const {message} = App.useApp();
  const client = useQueryClient();
  const scope = useQueryScope();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const controllerRef = useRef<AbortController | null>(null);
  const feedback = useRef({t, message});
  feedback.current = {t, message};
  const unread = useData<{count: number}>(['notifications', 'unread'], ({signal}) => get(`${BASE}/unread-count`, undefined, {signal}), {
    enabled, staleTime: 30_000, refetchInterval: 60_000, retry: 1,
  });
  useEffect(() => { void ensure(['common', 'notifications', 'host-dashboard']); }, [ensure, locale]);
  useLayoutEffect(() => {
    controllerRef.current?.abort(); controllerRef.current = null; busyRef.current = false;
    setBusy(false); setOpen(false);
    return () => {controllerRef.current?.abort();};
  }, [scope, enabled]);
  useEffect(() => {
    if (!enabled || !scope[0]) return;
    const stream = new AbortController();
    void consumeNotificationStream(stream.signal, event => {
      if (stream.signal.aborted || JSON.stringify(scope) !== getQueryScopeSnapshot()) return;
      void client.invalidateQueries({queryKey: scopedQueryKey(scope, ['notifications'])});
      if (event.type === 'PUBLISHED' && document.visibilityState === 'visible') {
        feedback.current.message.info(feedback.current.t('nav.notification.received', '收到一条新通知'));
      }
    });
    return () => stream.abort();
  }, [enabled, scope, client]);

  const markRead = useCallback(async (id?: string) => {
    if (!enabled || busyRef.current || JSON.stringify(scope) !== getQueryScopeSnapshot()) return false;
    const controller = new AbortController();
    controllerRef.current = controller;
    busyRef.current = true;
    setBusy(true);
    try {
      await put(id ? `${BASE}/${encodeURIComponent(id)}/read` : `${BASE}/read-all`, {}, {signal: controller.signal});
      if (controller.signal.aborted || JSON.stringify(scope) !== getQueryScopeSnapshot()) return false;
      await client.invalidateQueries({queryKey: scopedQueryKey(scope, ['notifications'])});
      return true;
    } catch (error) {
      if (!controller.signal.aborted && JSON.stringify(scope) === getQueryScopeSnapshot()) message.error(resolveApiErrorMessage(error, t('error.operationFailed', '操作失败，请重试')));
      return false;
    } finally {
      // A completed previous-workspace request must never unlock the current workspace's write.
      if (controllerRef.current === controller) {controllerRef.current = null; busyRef.current = false;
        if (!controller.signal.aborted) setBusy(false);}
    }
  }, [enabled, client, scope, message, t]);

  return <NotificationContext.Provider value={{enabled, unread: Math.max(0, Number(unread.data?.count) || 0), open, setOpen, busy, markRead}}>
    {children}
  </NotificationContext.Provider>;
}

export function useNotifications() {
  const value = useContext(NotificationContext);
  if (!value) throw new Error('NotificationProvider is required');
  return value;
}

/** Shared query keys deduplicate the home preview and the drawer's first page. */
export function useNotificationInbox(active: boolean, page = 0) {
  const {enabled} = useNotifications();
  return useData<Page<InboxNotification>>(['notifications', 'inbox', page], async ({signal}) =>
    normalizePage(await get(`${BASE}/inbox`, {page, size: 20}, {signal}), 20), {
    enabled: enabled && active, staleTime: 30_000, refetchInterval: active ? 60_000 : false, retry: 1,
  });
}
