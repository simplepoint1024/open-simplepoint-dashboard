import {get} from '@simplepoint/shared/api/methods';
import {ensureContextId} from '@simplepoint/shared/api/contextId';
import {getTenantId} from '@/store/tenant';
import {getRoleId} from '@/store/role';
import type {NotificationPushEvent} from './models';

const NOTIFICATION_BASE_URL = '/common/notifications';

const waitForReconnect = (delay: number, signal: AbortSignal) => new Promise<void>((resolve) => {
  if (signal.aborted) {
    resolve();
    return;
  }
  const onAbort = () => {
    window.clearTimeout(timeoutId);
    resolve();
  };
  const timeoutId = window.setTimeout(() => {
    signal.removeEventListener('abort', onAbort);
    resolve();
  }, delay);
  signal.addEventListener('abort', onAbort, {once: true});
});

export const consumeNotificationStream = async (
  signal: AbortSignal,
  onNotification: (event: NotificationPushEvent) => void,
) => {
  let reconnectDelay = 1_000;
  while (!signal.aborted) {
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    try {
      const tenantId = getTenantId()?.trim();
      const roleId = getRoleId(tenantId)?.trim();
      const contextId = await ensureContextId(tenantId, {roleId, signal});
      const headers = new Headers({Accept: 'text/event-stream'});
      if (tenantId) headers.set('X-Tenant-Id', tenantId);
      if (roleId) headers.set('X-Role-Id', roleId);
      if (contextId) headers.set('X-Context-Id', contextId);
      const response = await get<Response>(`${NOTIFICATION_BASE_URL}/stream`, undefined, {
        responseType: 'response',
        cache: 'no-store',
        headers,
        signal,
      });
      if (!response.ok || !response.body) {
        throw new Error(`Notification stream failed: ${response.status}`);
      }
      reconnectDelay = 1_000;
      reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      while (!signal.aborted) {
        const result = await reader.read();
        if (result.done) break;
        buffer = (buffer + decoder.decode(result.value, {stream: true}))
          .replace(/\r\n/g, '\n');
        let boundary = buffer.indexOf('\n\n');
        while (boundary >= 0) {
          const frame = buffer.slice(0, boundary);
          buffer = buffer.slice(boundary + 2);
          let eventName = 'message';
          const dataLines: string[] = [];
          frame.split('\n').forEach((line) => {
            if (line.startsWith('event:')) eventName = line.slice(6).trim();
            if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
          });
          if (eventName === 'notification') {
            try {
              onNotification(JSON.parse(dataLines.join('\n')) as NotificationPushEvent);
            } catch {
              onNotification({});
            }
          }
          boundary = buffer.indexOf('\n\n');
        }
      }
    } catch {
      if (signal.aborted) return;
    } finally {
      // Release the connection even when a callback/parser fails or the
      // authorization context changes between receiving headers and reading.
      await reader?.cancel().catch(() => {});
      reader?.releaseLock();
    }
    await waitForReconnect(reconnectDelay, signal);
    reconnectDelay = Math.min(30_000, reconnectDelay * 2);
  }
};
