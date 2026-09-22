import type {PersonalItem, PersonalMessage, PersonalTodo} from '@simplepoint/shared/personal/models';
export type PersonalKind = 'items' | 'todos' | 'messages';
export type PersonalRecord = PersonalItem | PersonalTodo | PersonalMessage;

/** UI guards complement the server's ownership, version and state checks. */
export function canPersonalAction(kind: PersonalKind, key: string, record?: PersonalRecord): boolean {
  if (key === 'add') return kind !== 'messages';
  if (key === 'readAll') return kind === 'messages';
  if (!record) return false;
  if (key === 'view') return true;
  if (kind === 'items') {
    const item = record as PersonalItem;
    if (item.deletedAt) return key === 'RESTORE';
    if (key === 'delete') return true;
    if (item.archived) return key === 'UNARCHIVE';
    const active = ['TODO', 'IN_PROGRESS'].includes(item.status);
    if (key === 'edit' || key === 'CANCEL') return active;
    if (key === 'START') return item.status === 'TODO';
    if (key === 'COMPLETE') return active && item.checklist.every(entry => entry.done);
    return !active && ['REOPEN', 'ARCHIVE'].includes(key);
  }
  if (kind === 'todos') {
    const todo = record as PersonalTodo;
    if (todo.source !== 'MANUAL') return false;
    return todo.status === 'PENDING' ? ['COMPLETE', 'CANCEL'].includes(key) : key === 'REOPEN';
  }
  const message = record as PersonalMessage;
  return key === 'READ' ? !message.readAt : key === 'UNREAD' ? !!message.readAt
    : key === 'ARCHIVE' ? !message.archived : key === 'UNARCHIVE' && message.archived;
}
