import assert from 'node:assert/strict';
import test from 'node:test';
import {setTimeout as delay} from 'node:timers/promises';
import {createSerialPoller} from './polling.ts';

test('slow requests never overlap, even when paused and immediately resumed', async () => {
  const start = createSerialPoller();
  let calls = 0;
  let release;
  const task = () => {calls++; return new Promise(resolve => {release = resolve;});};
  const stopFirst = start(task, 1, assert.fail);
  await delay(15);
  assert.equal(calls, 1);
  stopFirst();
  const stopSecond = start(task, 1, assert.fail);
  await delay(15);
  assert.equal(calls, 1);
  stopSecond();
  release();
  await delay(15);
  assert.equal(calls, 1, 'completion while hidden cannot restart polling');
});

test('failures are reported and later polls can recover; stopping clears scheduled work', async () => {
  const start = createSerialPoller();
  const failures = [];
  let calls = 0;
  const stop = start(async () => {calls++; if (calls === 1) throw new Error('offline');}, 1, error => failures.push(error.message));
  await delay(25);
  stop();
  const completedCalls = calls;
  await delay(10);
  assert.ok(calls >= 2);
  assert.equal(calls, completedCalls);
  assert.deepEqual(failures, ['offline']);
});
