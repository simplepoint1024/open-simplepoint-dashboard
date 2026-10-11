import assert from 'node:assert/strict';
import test from 'node:test';
import {isWorkflowEntryLocation, migrateWorkflowLocation, migrateWorkflowTabs, resolveWorkflowTargets} from './legacyWorkflow.ts';

const targets = {tasks: '/my/todos', items: '/my/items', management: '/workflow/management'};

test('first-visit personal deep links do not require a previously saved tab', () => {
  assert.equal(isWorkflowEntryLocation('/my/todos?view=completed&instance=flow', targets), true);
  assert.equal(isWorkflowEntryLocation('/my/items?instance=flow#detail', targets), true);
  assert.equal(isWorkflowEntryLocation('/my/messages?id=notice', {messages: '/my/messages'}), true);
  assert.equal(isWorkflowEntryLocation('/custom/work?task=task', {tasks: '/custom/work'}), true);
});
test('first-visit handling never authorizes a hidden, absent or lookalike workflow route', () => {
  assert.equal(isWorkflowEntryLocation('/my/todos?task=task', {}), false);
  assert.equal(isWorkflowEntryLocation('/my/todos/more', targets), false);
  assert.equal(isWorkflowEntryLocation('//outside.invalid/my/todos', targets), false);
  const hidden = resolveWorkflowTargets([{path: '/my/messages', component: 'common/personal/Messages', routeKind: 'hidden'}]);
  assert.equal(isWorkflowEntryLocation('/my/messages?id=notice', hidden), false);
});
test('old task and instance bookmarks preserve the selected record and view', () => {
  assert.equal(migrateWorkflowLocation('/workflow/tasks?id=task-1&view=completed', targets), '/my/todos?view=completed&task=task-1');
  assert.equal(migrateWorkflowLocation('/workflow/instances?id=flow-1#detail', targets), '/my/items?instance=flow-1#detail');
  assert.equal(migrateWorkflowLocation('/workflow/instances?scope=all', targets), '/workflow/management?scope=all');
});
test('only visible authorized internal personal/management routes can become targets', () => {
  assert.deepEqual(resolveWorkflowTargets([
    {path: '//outside.invalid', component: 'common/personal/Todos'},
    {path: '/hidden', component: 'common/personal/Todos', routeKind: 'hidden'},
    {children: [{path: '/organization/work', component: 'common/personal/Todos'}]},
    {path: '/disabled', component: 'workflow/Management', disabled: true},
  ]), {tasks: '/organization/work'});
  assert.equal(migrateWorkflowLocation('/workflow/instances', {}), '/dashboard');
});
test('route loading cannot prematurely discard a legacy tab; ready tabs merge without losing query parameters', () => {
  const tabs = [{key: '/workflow/tasks', location: '/workflow/tasks?id=task-1'}, {key: '/my/todos', location: '/my/todos'}];
  assert.deepEqual(migrateWorkflowTabs(tabs, {}, false), tabs);
  assert.deepEqual(migrateWorkflowTabs(tabs, targets, true), [{key: '/my/todos', location: '/my/todos?task=task-1'}]);
  assert.equal(migrateWorkflowLocation('/my/todos?id=manual-1', targets), '/my/todos?id=manual-1', 'existing manual links remain distinguishable');
});
