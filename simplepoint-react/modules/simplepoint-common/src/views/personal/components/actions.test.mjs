import assert from 'node:assert/strict';
import test from 'node:test';
import {canPersonalAction} from './actions.ts';

test('personal toolbar guards selection, lifecycle, archive and trash transitions', () => {
  assert.equal(canPersonalAction('items', 'edit'), false);
  const item = {status: 'TODO', checklist: [], archived: false, deletedAt: null};
  for (const key of ['edit', 'START', 'COMPLETE', 'CANCEL', 'delete', 'view']) assert.equal(canPersonalAction('items', key, item), true);
  assert.equal(canPersonalAction('items', 'COMPLETE', {...item, checklist: [{done: false}]}), false);
  assert.equal(canPersonalAction('items', 'edit', {...item, archived: true}), false);
  assert.equal(canPersonalAction('items', 'RESTORE', {...item, deletedAt: 'now'}), true);
  assert.equal(canPersonalAction('items', 'delete', {...item, deletedAt: 'now'}), false);
});

test('business todos cannot be manually completed and messages cannot be created or deleted', () => {
  for (const key of ['COMPLETE', 'CANCEL', 'REOPEN', 'edit']) assert.equal(canPersonalAction('todos', key, {source: 'WORKFLOW', status: 'PENDING'}), false);
  assert.equal(canPersonalAction('todos', 'COMPLETE', {source: 'MANUAL', status: 'PENDING'}), true);
  assert.equal(canPersonalAction('todos', 'REOPEN', {source: 'MANUAL', status: 'DONE'}), true);
  assert.equal(canPersonalAction('messages', 'add'), false);
  assert.equal(canPersonalAction('messages', 'delete', {readAt: null, archived: false}), false);
  assert.equal(canPersonalAction('messages', 'READ', {readAt: null}), true);
  assert.equal(canPersonalAction('messages', 'UNREAD', {readAt: 'now'}), true);
});
