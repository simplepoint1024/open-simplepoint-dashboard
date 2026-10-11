import assert from 'node:assert/strict';
import {test} from 'node:test';
import {deploymentWorkloadFilters} from './deploymentQueries.ts';
test('deployment queries constrain the actual pool and count all nonterminal workloads independently of the history page', () => {
  const history = JSON.parse(deploymentWorkloadFilters('pool-a').$query);
  assert.deepEqual(history, {version: 1, where: {op: 'and', children: [{field: 'poolId', op: 'equals', value: 'pool-a'}]}});
  const active = JSON.parse(deploymentWorkloadFilters('pool-a', true).$query);
  assert.deepEqual(active.where.children, [history.where.children[0], {field: 'status', op: 'not:in', value: ['SUCCEEDED', 'FAILED', 'LOST', 'CANCELLED']}]);
});

test('header and grouped conditions intersect the pool instead of replacing either query', () => {
  const user = {op: 'or', children: [{field: 'status', op: 'equals', value: 'FAILED'}, {field: 'poolId', op: 'equals', value: 'other'}]};
  const filters = {assignedNodeId: 'equals:node-1', $query: JSON.stringify({version: 1, where: user})};
  const history = deploymentWorkloadFilters('pool-a', false, filters);
  assert.equal(history.assignedNodeId, 'equals:node-1');
  assert.deepEqual(JSON.parse(history.$query).where.children, [{field: 'poolId', op: 'equals', value: 'pool-a'}, user]);
  assert.deepEqual(JSON.parse(filters.$query).where, user, 'caller-owned filters are immutable');
  assert.equal(JSON.parse(deploymentWorkloadFilters('pool-a', true).$query).where.children.length, 2,
    'active workload count is independent of the visible page filters');
  assert.throws(() => deploymentWorkloadFilters('pool-a', false, {$query: '{"version":2}'}));
});
