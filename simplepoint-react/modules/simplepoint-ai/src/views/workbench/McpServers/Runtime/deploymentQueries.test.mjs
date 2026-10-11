import assert from 'node:assert/strict';
import {test} from 'node:test';
import {deploymentWorkloadFilters} from './deploymentQueries.ts';
test('deployment queries constrain the actual pool and count all nonterminal workloads independently of the history page', () => {
  const history = JSON.parse(deploymentWorkloadFilters('pool-a').$query);
  assert.deepEqual(history, {version: 1, where: {op: 'and', children: [{field: 'poolId', op: 'equals', value: 'pool-a'}]}});
  const active = JSON.parse(deploymentWorkloadFilters('pool-a', true).$query);
  assert.deepEqual(active.where.children, [history.where.children[0], {field: 'status', op: 'not:in', value: ['SUCCEEDED', 'FAILED', 'LOST', 'CANCELLED']}]);
});
