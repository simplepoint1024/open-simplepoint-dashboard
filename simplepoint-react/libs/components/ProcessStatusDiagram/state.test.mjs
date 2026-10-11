import assert from 'node:assert/strict';
import test from 'node:test';
import {activityMarkers, markerClass} from './state.ts';

test('a repeated node retains its individual occurrences while its active execution wins on canvas', () => {
  const activities = [
    {id: 'past', taskId: 'past-task', activityId: 'review', status: 'COMPLETED'},
    {id: 'current', taskId: 'current-task', activityId: 'review', status: 'ACTIVE'},
  ];
  assert.equal(activityMarkers(activities).get('review'), 'ACTIVE');
  assert.deepEqual(activities.map(item => item.id), ['past', 'current']);
});

for (const state of ['REJECTED', 'WITHDRAWN', 'CANCELED']) {
  test(`${state} is not hidden by an earlier completed occurrence of the same node`, () => {
    assert.equal(activityMarkers([{activityId: 'review', status: state},
      {activityId: 'review', status: 'COMPLETED'}]).get('review'), state);
    assert.notEqual(markerClass(state), 'workflow-status-completed');
  });
}

test('active engine IDs cover concurrently started activities absent from the history snapshot', () => {
  assert.equal(activityMarkers([], ['new-task']).get('new-task'), 'ACTIVE');
  assert.equal(markerClass('REJECTED'), 'workflow-status-failed');
});
