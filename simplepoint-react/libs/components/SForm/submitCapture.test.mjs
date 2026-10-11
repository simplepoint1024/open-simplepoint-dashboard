import {test} from 'node:test';
import assert from 'node:assert/strict';
import {guardFormSubmit} from './submitCapture.ts';

const eventFor = (target, bubbles = true) => ({target, nativeEvent: {bubbles}, cancelled: false, stopped: false,
  preventDefault() {this.cancelled = true;}, stopPropagation() {this.stopped = true;}});
test('genuine submit capture acquires its lease synchronously before queued engine validation', () => {
  const form = {}, event = eventFor(form); let busy = false;
  assert.equal(guardFormSubmit(event, form, true, () => {assert.equal(busy, false); busy = true;}), 'accepted');
  assert.equal(busy, true); assert.equal(event.cancelled, false); assert.equal(event.stopped, false);
  const duplicate = eventFor(form);
  assert.equal(guardFormSubmit(duplicate, form, true, () => !busy), 'blocked');
  assert.equal(duplicate.cancelled, true); assert.equal(duplicate.stopped, true);
});
test('RJSF imperative probe is ignored and its following actual submit remains usable', () => {
  const form = {}, probe = eventFor(form, false); let calls = 0;
  probe.preventDefault();
  assert.equal(guardFormSubmit(probe, form, true, () => {calls++;}), 'ignored');
  assert.equal(calls, 0); assert.equal(probe.stopped, false);
  assert.equal(guardFormSubmit(eventFor(form), form, true, () => {calls++;}), 'accepted');
  assert.equal(calls, 1);
});
test('disabled or discard-owned form events are blocked without calling preflight; nested forms are ignored', () => {
  const form = {}, blocked = eventFor(form); let calls = 0;
  assert.equal(guardFormSubmit(blocked, form, false, () => {calls++;}), 'blocked');
  assert.equal(blocked.cancelled, true); assert.equal(blocked.stopped, true); assert.equal(calls, 0);
  const unrelated = eventFor({});
  assert.equal(guardFormSubmit(unrelated, form, true, () => {calls++;}), 'ignored');
  assert.equal(calls, 0); assert.equal(unrelated.cancelled, false);
});
