export type InboxNotification = {
  id: string;
  title: string;
  content: string;
  category: 'ANNOUNCEMENT' | 'SYSTEM' | 'MAINTENANCE' | 'SECURITY';
  priority: 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT';
  linkUrl?: string;
  publishAt: string;
  expireAt?: string;
  read: boolean;
  readAt?: string;
};

export type NotificationPushEvent = {
  type?: 'PUBLISHED' | 'REVOKED' | 'USER_STATE_CHANGED';
  notificationId?: string;
  occurredAt?: string;
};

/** Reject protocol-relative and executable links; ordinary app paths remain internal. */
export function notificationTarget(value?: string): {url: string; external: boolean} | undefined {
  if (!value || value !== value.trim() || /[\u0000-\u001f\\]/.test(value)) return;
  if (value.startsWith('/') && !value.startsWith('//')) return {url: value, external: false};
  try {
    const url = new URL(value);
    if (url.protocol === 'https:' && !url.username && !url.password) return {url: url.href, external: true};
  } catch { /* Invalid links are displayed as text, never executed. */ }
}
