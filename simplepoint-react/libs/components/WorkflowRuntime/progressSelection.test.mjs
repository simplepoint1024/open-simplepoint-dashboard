import assert from 'node:assert/strict';
import {test} from 'node:test';
import {initialProgressSelection, progressSection} from './progressSelection.ts';

test('business history opens the immutable form and preserves explicit navigation', () => {
  assert.equal(progressSection('app-form:submission'), 'business');
  assert.equal(progressSection('app-form:submission', 'nodes'), 'nodes');
  assert.equal(progressSection('app-form:submission', 'actions'), 'actions');
  assert.equal(progressSection(undefined), 'nodes');
  assert.equal(progressSection('ordinary:record', 'business'), 'nodes');
});

const running = {activeActivityIds: ['review'], activities: [{activityId: 'review', activityType: 'userTask'}]};
test('reopening a just-completed task does not initialize selection from invalidated active cache', () => {
  assert.equal(initialProgressSelection(running, true), undefined);
  assert.deepEqual(initialProgressSelection({...running, activeActivityIds: []}, false), {node: 'review', activeOnly: false});
});
test('current execution has priority and completed processes select the latest human node', () => {
  assert.deepEqual(initialProgressSelection(running, false), {node: 'review', activeOnly: true});
  assert.deepEqual(initialProgressSelection({activeActivityIds: [], activities: [
    ...running.activities, {activityId: 'finalReview', activityType: 'userTask'}, {activityId: 'end', activityType: 'endEvent'},
  ]}, false), {node: 'finalReview', activeOnly: false});
  assert.equal(initialProgressSelection(undefined, false), undefined);
});
