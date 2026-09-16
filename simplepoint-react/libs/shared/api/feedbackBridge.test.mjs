import assert from 'node:assert/strict';
import test from 'node:test';
import {
  getFeedbackBridge,
  registerFeedbackBridge,
  waitForFeedbackBridge,
} from './feedbackBridge.ts';

const createBridge = (name) => ({
  message: {name},
  modal: {name},
});

test('feedback bridge registers and clears the active instance', () => {
  const bridge = createBridge('first');
  const unregister = registerFeedbackBridge(bridge);

  assert.equal(getFeedbackBridge(), bridge);

  unregister();
  assert.equal(getFeedbackBridge(), undefined);

  // Cleanup is intentionally idempotent for React effect lifecycles.
  unregister();
  assert.equal(getFeedbackBridge(), undefined);
});

test('stale cleanup cannot clear a newer registration', () => {
  const firstBridge = createBridge('first');
  const secondBridge = createBridge('second');
  const unregisterFirst = registerFeedbackBridge(firstBridge);
  const unregisterSecond = registerFeedbackBridge(secondBridge);

  assert.equal(getFeedbackBridge(), secondBridge);

  unregisterFirst();
  assert.equal(getFeedbackBridge(), secondBridge);

  unregisterSecond();
  assert.equal(getFeedbackBridge(), undefined);
});

test('waits for host registration without using a context-free fallback', async () => {
  const bridge = createBridge('delayed');
  const waiting = waitForFeedbackBridge(100);
  const unregister = registerFeedbackBridge(bridge);

  assert.equal(await waiting, bridge);
  unregister();
  assert.equal(await waitForFeedbackBridge(1), undefined);
});
