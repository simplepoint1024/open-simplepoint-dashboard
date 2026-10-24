import assert from 'node:assert/strict';
import test from 'node:test';
import {beginSessionExit, isSessionExiting, subscribeSessionExit} from './sessionLifecycle.ts';

test('exit fences synchronously, notifies once and survives a second federation module instance', async () => {
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'window');
  const browser = new EventTarget();
  Object.defineProperty(globalThis, 'window', {configurable: true, value: browser});
  try {
    let calls = 0;
    const unsubscribe = subscribeSessionExit(() => {assert(isSessionExiting()); calls++;});
    assert.equal(isSessionExiting(), false);
    const remote = await import('./sessionLifecycle.ts?remote');
    remote.beginSessionExit();
    assert(isSessionExiting());
    beginSessionExit();
    assert.equal(calls, 1);
    unsubscribe();
    browser.dispatchEvent(new Event('sp-session-exit'));
    assert.equal(calls, 1);
    // A new document can bootstrap normally; the fence is not persisted as a preference.
    Object.defineProperty(globalThis, 'window', {configurable: true, value: new EventTarget()});
    assert.equal(isSessionExiting(), false);
  } finally {
    if (previous) Object.defineProperty(globalThis, 'window', previous);
    else delete globalThis.window;
  }
});

test('server-side imports do not require a browser', () => {
  assert.equal(isSessionExiting(), false);
  beginSessionExit();
  subscribeSessionExit(() => assert.fail('no browser event'))();
});
