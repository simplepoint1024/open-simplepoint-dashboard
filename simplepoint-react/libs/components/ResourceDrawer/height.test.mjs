import assert from 'node:assert/strict';
import test from 'node:test';
import {drawerHeightBounds, clampDrawerHeight} from './height.ts';

test('configuration drawer preserves its standard height and clamps dragging to the viewport', () => {
  assert.deepEqual(drawerHeightBounds(900), {minimum: 240, maximum: 820});
  assert.equal(clampDrawerHeight(480, 900), 480);
  assert.equal(clampDrawerHeight(1, 900), 240);
  assert.equal(clampDrawerHeight(1000, 900), 820);
});

test('short viewports do not create contradictory resize bounds or overflow the available height', () => {
  assert.deepEqual(drawerHeightBounds(300), {minimum: 220, maximum: 220});
  assert.equal(clampDrawerHeight(480, 300), 220);
  assert.equal(clampDrawerHeight(480, 200), 120);
  assert.equal(clampDrawerHeight(480, 600, 420), 480);
});
