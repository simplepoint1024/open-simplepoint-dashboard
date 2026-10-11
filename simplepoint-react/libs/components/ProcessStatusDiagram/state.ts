export type ProcessActivityState = {
  id?: string;
  taskId?: string;
  activityId: string;
  activityName?: string;
  activityType?: string;
  status: string;
  startedAt?: string;
  endedAt?: string;
  assignee?: string;
};

const priority: Record<string, number> = {
  COMPLETED: 1, CANCELED: 2, WITHDRAWN: 2, REJECTED: 3, FAILED: 3, ACTIVE: 4,
};

/** Canvas aggregates a BPMN node; execution details must still retain every distinct occurrence. */
export function activityMarkers(activities: readonly ProcessActivityState[], activeIds: readonly string[] = []) {
  const result = new Map<string, string>();
  for (const activity of activities) {
    const current = result.get(activity.activityId);
    if (!current || (priority[activity.status] ?? 0) >= (priority[current] ?? 0)) result.set(activity.activityId, activity.status);
  }
  activeIds.forEach(id => result.set(id, 'ACTIVE'));
  return result;
}

export function markerClass(status: string) {
  return status === 'ACTIVE' ? 'workflow-status-active' : status === 'COMPLETED' ? 'workflow-status-completed'
    : status === 'FAILED' || status === 'REJECTED' ? 'workflow-status-failed' : 'workflow-status-canceled';
}
