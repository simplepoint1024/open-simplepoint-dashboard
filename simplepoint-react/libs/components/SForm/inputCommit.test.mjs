import test from 'node:test';
import assert from 'node:assert/strict';
import {commitFormInput} from './inputCommit.ts';

test('external editor input commits through the real boundary before an immediate submit reads it', () => {
  let current = '', pending, flushes = 0;
  const engineChange = () => {pending = 'id >= 0';};
  const flush = update => {flushes++; update(); current = pending;};
  assert.equal(commitFormInput(engineChange, true, flush), true);
  assert.equal(current, 'id >= 0'); assert.equal(flushes, 1);
});
test('readonly or unmounted editor input does not call an update or flush', () => {
  assert.equal(commitFormInput(() => assert.fail('update'), false, () => assert.fail('flush')), false);
});
test('external editor commit propagates engine errors without retries or replacing the value', () => {
  const failure = new Error('engine update failed'); let calls = 0;
  assert.throws(() => commitFormInput(() => {calls++; throw failure;}, true, update => update()), error => error === failure);
  assert.equal(calls, 1);
});
