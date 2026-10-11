export const terminalWorkloadStatuses = ['SUCCEEDED', 'FAILED', 'LOST', 'CANCELLED'] as const;
export function deploymentWorkloadFilters(poolId: string, activeOnly = false, filters: Record<string, string> = {}) {
  const children: object[] = [{field: 'poolId', op: 'equals', value: poolId}];
  if (activeOnly) children.push({field: 'status', op: 'not:in', value: [...terminalWorkloadStatuses]});
  if (filters.$query) {
    const document = JSON.parse(filters.$query);
    if (document.version !== 1 || !document.where || typeof document.where !== 'object') throw new Error('Invalid workload query');
    children.push(document.where);
  }
  return {...filters, $query: JSON.stringify({version: 1, where: {op: 'and', children}})};
}
