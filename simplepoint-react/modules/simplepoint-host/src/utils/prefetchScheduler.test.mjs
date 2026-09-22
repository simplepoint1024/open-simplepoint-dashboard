import assert from 'node:assert/strict';
import test from 'node:test';
import {setImmediate} from 'node:timers/promises';
import {createPrefetchScheduler, permitsSpeculation} from './prefetchScheduler.ts';

test('hover must dwell; leaving cancels work and duplicate events coalesce', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const scheduler = createPrefetchScheduler();
  const calls = [];
  scheduler.schedule('a', async () => calls.push('a'));
  scheduler.schedule('a', async () => calls.push('duplicate'));
  scheduler.schedule('b', async () => calls.push('b'));
  scheduler.cancel('b');
  t.mock.timers.tick(179);
  await setImmediate();
  assert.deepEqual(calls, []);
  t.mock.timers.tick(1);
  await setImmediate();
  assert.deepEqual(calls, ['a']);
});

test('limits active downloads, cancels queued work and recovers after rejection', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const scheduler = createPrefetchScheduler(2, 1);
  const calls = [];
  let release;
  scheduler.schedule('a', () => new Promise(resolve => {calls.push('a'); release = resolve;}));
  scheduler.schedule('b', async () => {calls.push('b'); throw new Error('offline');});
  scheduler.schedule('c', async () => calls.push('c'));
  scheduler.schedule('d', async () => calls.push('d'));
  t.mock.timers.tick(1);
  scheduler.cancel('c');
  await setImmediate();
  assert.deepEqual(calls, ['a', 'b', 'd']);
  release();
  await setImmediate();
  scheduler.schedule('a', async () => calls.push('retry'));
  t.mock.timers.tick(1);
  await setImmediate();
  assert.equal(calls.at(-1), 'retry');
});

test('respects data saver and slow links, including browsers without Network Information API', () => {
  assert.equal(permitsSpeculation(), true);
  assert.equal(permitsSpeculation({effectiveType: '4g'}), true);
  assert.equal(permitsSpeculation({saveData: true}), false);
  for (const effectiveType of ['slow-2g', '2g', '3g']) {
    assert.equal(permitsSpeculation({effectiveType}), false);
  }
});
