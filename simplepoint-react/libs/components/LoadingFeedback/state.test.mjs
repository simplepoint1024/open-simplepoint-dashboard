import assert from 'node:assert/strict';
import test from 'node:test';
import {createLoadingState} from './state.ts';

test('quick loads never show progress; pending loads show it after 200ms', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const state = createLoadingState();
  let end = state.register();
  assert.equal(state.getSnapshot().busy, true);
  t.mock.timers.tick(199);
  assert.equal(state.getSnapshot().visible, false);
  end();
  await Promise.resolve();
  t.mock.timers.tick(2000);
  assert.deepEqual(state.getSnapshot(), {busy: false, visible: false, slow: false});
  end = state.register();
  t.mock.timers.tick(200);
  assert.equal(state.getSnapshot().visible, true);
  t.mock.timers.tick(1000);
  assert.equal(state.getSnapshot().slow, true);
  end();
  await Promise.resolve();
  assert.equal(state.getSnapshot().busy, false);
});

test('MF-to-schema handoff preserves feedback; concurrent tasks and duplicate cleanup are safe', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const state = createLoadingState();
  const endModule = state.register();
  t.mock.timers.tick(200);
  const visible = state.getSnapshot();
  endModule();
  const endSchema = state.register();
  await Promise.resolve();
  assert.equal(state.getSnapshot(), visible);
  const endData = state.register();
  endSchema();
  endSchema();
  await Promise.resolve();
  assert.equal(state.getSnapshot().busy, true);
  endData();
  await Promise.resolve();
  assert.equal(state.getSnapshot().busy, false);
});
