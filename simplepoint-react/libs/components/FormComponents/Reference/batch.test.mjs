import test from 'node:test';
import assert from 'node:assert/strict';
import {batchReferenceLookups} from './batch.ts';

const row = id => ({id, label: id, version: 1, values: {}});

test('forty inline rows share one bounded hydration request and receive only their own result', async () => {
  const calls = [];
  const resolve = batchReferenceLookups(async ids => {calls.push(ids); return ids.map(row);});
  const results = await Promise.all(Array.from({length: 40}, (_, i) => resolve([String(i)], new AbortController().signal)));
  assert.equal(calls.length, 1);
  assert.equal(calls[0].length, 40);
  assert.deepEqual(results, Array.from({length: 40}, (_, i) => [row(String(i))]));
});

test('combined queues are split at the server limit without dropping cross-page selections', async () => {
  const sizes = [];
  const resolve = batchReferenceLookups(async ids => {sizes.push(ids.length); return ids.map(row);});
  const ids = Array.from({length: 240}, (_, i) => String(i));
  const result = await Promise.all([0, 80, 160].map(start => resolve(ids.slice(start, start + 80), new AbortController().signal)));
  assert.deepEqual(sizes, [100, 100, 40]);
  assert.deepEqual(result.flat().map(row => row.id), ids);
});

test('one cancelled owner cannot cancel a shared read needed by another owner', async () => {
  let finish, wire;
  const resolve = batchReferenceLookups((ids, signal) => {wire = signal; return new Promise(done => {finish = () => done(ids.map(row));});});
  const first = new AbortController(), second = new AbortController();
  const abandoned = resolve(['a'], first.signal);
  const kept = resolve(['b'], second.signal);
  const rejected = assert.rejects(abandoned, {name: 'AbortError'});
  await Promise.resolve();
  first.abort();
  assert.equal(wire.aborted, false);
  finish();
  await rejected;
  assert.deepEqual(await kept, [row('b')]);
});

test('last owner cancellation aborts transport and fences late response data', async () => {
  let finish, wire;
  const resolve = batchReferenceLookups((ids, signal) => {wire = signal; return new Promise(done => {finish = () => done(ids.map(row));});});
  const owner = new AbortController();
  const result = resolve(['a'], owner.signal);
  const rejected = assert.rejects(result, {name: 'AbortError'});
  await Promise.resolve();
  owner.abort();
  assert.equal(wire.aborted, true);
  finish();
  await rejected;
});
