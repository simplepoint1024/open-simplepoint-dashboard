import assert from 'node:assert/strict';
import test from 'node:test';
import {observeDiagramViewport} from './viewport.ts';

function fixture(t) {
  let callback, disconnected = false, fits = 0, sequence = 0;
  const frames = new Map(), target = {clientWidth: 800, clientHeight: 480, isConnected: true};
  t.mock.method(globalThis, 'requestAnimationFrame', run => {frames.set(++sequence, run); return sequence;});
  t.mock.method(globalThis, 'cancelAnimationFrame', id => frames.delete(id));
  t.mock.method(globalThis, 'ResizeObserver', function (run) {
    callback = run;
    return {observe(element) {assert.equal(element, target);}, disconnect() {disconnected = true;}};
  });
  const stop = observeDiagramViewport(target, () => fits++);
  return {stop, resize: (width, height) => {
    target.clientWidth = width; target.clientHeight = height;
    callback([{contentRect: {width, height}}]);
  }, hideBeforeFrame: () => {target.clientWidth = 0;},
    flush: () => {const queued = [...frames.values()]; frames.clear(); queued.forEach(run => run());},
    fits: () => fits, disconnected: () => disconnected, pending: () => frames.size};
}

// Node has no browser viewport globals; mocks are restored after each isolated test.
globalThis.requestAnimationFrame ??= () => 0;
globalThis.cancelAnimationFrame ??= () => {};
globalThis.ResizeObserver ??= class {};

test('viewport fits initial and changed sizes without resetting zoom on unchanged notifications', t => {
  const f = fixture(t);
  f.resize(800, 480); f.flush(); assert.equal(f.fits(), 1);
  f.resize(800, 480); f.flush(); assert.equal(f.fits(), 1);
  f.resize(320, 480); f.flush(); assert.equal(f.fits(), 2);
  f.stop();
});
test('viewport ignores hidden canvases and coalesces size changes into one animation frame', t => {
  const f = fixture(t);
  f.resize(0, 480); f.resize(320, 0); assert.equal(f.pending(), 0);
  f.resize(800, 480); f.resize(320, 480); assert.equal(f.pending(), 1);
  f.flush(); assert.equal(f.fits(), 1); f.stop();
});
test('disposed viewers cancel queued fitting and reject late observer callbacks', t => {
  const f = fixture(t);
  f.resize(320, 480); f.stop(); f.flush(); f.resize(400, 480); f.flush();
  assert.equal(f.fits(), 0); assert.equal(f.pending(), 0); assert.equal(f.disconnected(), true);
});

test('a canvas hidden between observation and animation cannot emit an invalid SVG scale', t => {
  const f = fixture(t);
  f.resize(320, 480); f.hideBeforeFrame(); f.flush(); assert.equal(f.fits(), 0);
  f.resize(320, 480); f.flush(); assert.equal(f.fits(), 1);
  f.resize(400, 480); f.resize(0, 480); f.flush(); assert.equal(f.fits(), 1);
  f.resize(400, 480); f.flush(); assert.equal(f.fits(), 2); f.stop();
});
