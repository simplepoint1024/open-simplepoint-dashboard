export const terminalWorkloadStatuses = ['SUCCEEDED', 'FAILED', 'LOST', 'CANCELLED'] as const;
export function deploymentWorkloadFilters(poolId: string, activeOnly = false) {
  const children: object[] = [{field: 'poolId', op: 'equals', value: poolId}];
  if (activeOnly) children.push({field: 'status', op: 'not:in', value: [...terminalWorkloadStatuses]});
  return {$query: JSON.stringify({version: 1, where: {op: 'and', children}})};
}
