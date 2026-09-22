export type ItemStatus = 'TODO' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED';
export interface ChecklistEntry {id: string; title: string; done: boolean}
export interface PersonalItem {
  id: string; version: number; title: string; description: string; category: string;
  priority: number; status: ItemStatus; startAt: string | null; dueAt: string | null;
  completedAt: string | null; pinned: boolean; archived: boolean; deletedAt: string | null;
  checklist: ChecklistEntry[]; createdAt: string; updatedAt: string;
}
export interface PersonalTodo {
  id: string; version: number; title: string; content: string; priority: number;
  status: 'PENDING' | 'DONE' | 'CANCELLED'; dueAt: string | null; completedAt: string | null;
  source: string; linkPath: string | null; createdAt: string;
}
export interface PersonalMessage {
  id: string; version: number; title: string; content: string; priority: number;
  source: string; linkPath: string | null; readAt: string | null; archived: boolean; createdAt: string;
}
export interface PersonalEvent {id: string; action: string; detail: string | null; createdAt: string}
export interface PersonalOverview {
  activeItems: number; overdueItems: number; pendingTodos: number; unreadMessages: number;
  items: PersonalItem[]; todos: PersonalTodo[]; messages: PersonalMessage[];
}
export const personalApi = '/common/personal';
/** Business deep links must never become external navigation or executable URLs. */
export function safePersonalPath(path: string | null | undefined): path is string {
  return !!path && path.startsWith('/') && !path.startsWith('//') && !/[\\\u0000-\u001f\u007f]/.test(path);
}
