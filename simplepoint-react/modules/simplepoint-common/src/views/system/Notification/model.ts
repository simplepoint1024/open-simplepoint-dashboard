import {formatDateTime} from '@simplepoint/shared/format/temporal';
export type NotificationRow = {id: string; title?: string; content?: string; category?: string; priority?: string;
  audienceType?: string; audienceId?: string; status?: 'DRAFT' | 'PUBLISHED' | 'REVOKED'; publishAt?: string; expireAt?: string};
export type NotificationAction = 'publish' | 'revoke' | 'duplicate';
export const notificationActionStatus = {publish: 'DRAFT', revoke: 'PUBLISHED', duplicate: 'REVOKED'} as const;

export function usableNotification(row: unknown): row is NotificationRow {
  const id = (row as NotificationRow | undefined)?.id;
  return typeof id === 'string' && !!id.trim() && !/^\*+$/.test(id.trim());
}
export function canNotificationAction(action: NotificationAction, row: unknown): row is NotificationRow {
  return usableNotification(row) && row.status === notificationActionStatus[action];
}
export function notificationDate(value: unknown): string {
  if (typeof value !== 'string' || !value.trim()) return '-';
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? formatDateTime(value) : '-';
}

/** A different audience directory never inherits another kind's persisted identifier. */
export function notificationFormData(next: Record<string, unknown>, previous: Record<string, unknown>) {
  if ((next.audienceType ?? 'ALL') === (previous.audienceType ?? 'ALL')) return next;
  return {...next, audienceId: undefined};
}
export function notificationFormUi(draft: Record<string, unknown>, audienceReadable = true, audienceWritable = true) {
  const protectedType = audienceReadable && audienceWritable ? {} : {audienceType: {'ui:readonly': true}};
  if (!audienceReadable) return {...protectedType, audienceId: {'ui:readonly': true}};
  switch (draft.audienceType ?? 'ALL') {
    case 'ALL': return {...protectedType, audienceId: {'ui:widget': 'hidden'}};
    case 'USER': return {...protectedType, audienceId: {'ui:widget': 'UserPicker'}};
    case 'TENANT': return {...protectedType, audienceId: {'ui:widget': 'TenantSelect', 'ui:options': {directory: 'platform'}}};
    // A protected/unknown discriminant cannot safely choose an editable ID directory.
    default: return {...protectedType, audienceId: {'ui:readonly': true}};
  }
}
