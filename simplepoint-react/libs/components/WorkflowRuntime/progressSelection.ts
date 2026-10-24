type DiagramSelection = {activeActivityIds: string[]; activities: Array<{activityId: string; activityType?: string}>};

export type ProgressSection = 'nodes' | 'actions' | 'business';

/** Business process participants review their submitted form first; navigation remains explicit. */
export function progressSection(businessKey?: string, chosen?: ProgressSection): ProgressSection {
  const hasBusiness = Boolean(businessKey?.startsWith('app-form:'));
  if (chosen && (chosen !== 'business' || hasBusiness)) return chosen;
  return hasBusiness ? 'business' : 'nodes';
}

/** Invalidated cached data may describe a task that just finished; wait for the authoritative refresh. */
export function initialProgressSelection(diagram: DiagramSelection | undefined, fetching: boolean) {
  if (!diagram || fetching) return undefined;
  const active = diagram.activeActivityIds[0];
  return {node: active ?? [...diagram.activities].reverse().find(item => item.activityType === 'userTask')?.activityId,
    activeOnly: Boolean(active)};
}
