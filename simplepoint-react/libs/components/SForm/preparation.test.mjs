import test from 'node:test';
import assert from 'node:assert/strict';
import {beginPreparation} from './preparation.ts';
import {guardFormSubmit} from './submitCapture.ts';

test('real engine preparation locks submission in the same frame and tokens release independently', () => {
  const pending = new Set(), changes = [], form = {}, events = [];
  const event = {target: form, nativeEvent: {bubbles: true}, preventDefault: () => events.push('cancel'), stopPropagation: () => events.push('stop')};
  const first = beginPreparation(pending, true, busy => changes.push(busy));
  const second = beginPreparation(pending, true, busy => changes.push(busy));
  assert.equal(guardFormSubmit(event, form, pending.size === 0, () => true), 'blocked');
  first(); first(); assert.equal(pending.size, 1);
  assert.equal(guardFormSubmit(event, form, pending.size === 0, () => true), 'blocked');
  second(); assert.equal(guardFormSubmit(event, form, pending.size === 0, () => true), 'accepted');
  assert.deepEqual(changes, [true, true, true, false]);
  assert.equal(beginPreparation(pending, false, () => assert.fail()), undefined);
});
test('an old preparation release cannot unlock a fresh mounted form session', () => {
  const pending = new Set(), first = beginPreparation(pending, true, () => {});
  pending.clear(); const current = beginPreparation(pending, true, () => {});
  first(); assert.equal(pending.size, 1); current(); assert.equal(pending.size, 0);
});
