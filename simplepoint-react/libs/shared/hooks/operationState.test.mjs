import assert from 'node:assert/strict';
import {test} from 'node:test';
import {operationIsCurrent, acquireOperation, releaseOperation, resetOperation} from './operationState.ts';

test('old auth/resource owners and aborted/unmounted requests never remain current', () => {
  const request = new AbortController();
  assert.equal(operationIsCurrent('scope-a', 'scope-a', 'resource-a', 'resource-a', true, request.signal), true);
  assert.equal(operationIsCurrent('scope-a', 'scope-b', 'resource-a', 'resource-a', true, request.signal), false);
  assert.equal(operationIsCurrent('scope-a', 'scope-a', 'resource-a', 'resource-b', true, request.signal), false);
  assert.equal(operationIsCurrent('scope-a', 'scope-a', 'resource-a', 'resource-a', false, request.signal), false);
  request.abort(); assert.equal(operationIsCurrent('scope-a', 'scope-a', 'resource-a', 'resource-a', true, request.signal), false);
});
test('same-frame double acquisition cannot start a second operation', () => {
  const slot = {current: undefined}; const first = acquireOperation(slot);
  assert.ok(first); assert.equal(acquireOperation(slot), undefined);
  assert.equal(releaseOperation(slot, first), true); assert.ok(acquireOperation(slot));
});
test('in-place scope reset aborts transport, allows a new write and ignores late old completion', () => {
  const slot = {current: undefined}; const old = acquireOperation(slot);
  resetOperation(slot); assert.equal(old.signal.aborted, true);
  const next = acquireOperation(slot); assert.ok(next); assert.notEqual(next, old);
  assert.equal(releaseOperation(slot, old), false); assert.equal(slot.current, next);
  assert.equal(acquireOperation(slot), undefined);
  assert.equal(releaseOperation(slot, next), true); assert.equal(slot.current, undefined);
});
