import assert from 'node:assert/strict';
import test from 'node:test';
import {committedReactFiber} from './cdp-react-probe.mjs';

test('DOM metadata probes read the current commit even when the cached fiber is stale', () => {
  const element = {};
  const current = {stateNode: {}}, old = {stateNode: {current}};
  const staleRow = {stateNode: element, return: old, memoizedProps: {metadata: 'old'}};
  const row = {stateNode: element, return: current, memoizedProps: {metadata: 'current'}};
  current.stateNode.current = current;
  current.child = {stateNode: {}, sibling: row};
  old.child = staleRow;
  element.__reactFiber$test = staleRow;
  assert.equal(committedReactFiber(element), row);
  element.__reactFiber$test = row;
  assert.equal(committedReactFiber(element), row);
});

test('metadata probes reject detached nodes instead of observing old values', () => {
  assert.throws(() => committedReactFiber({}), /fiber missing/);
  const element = {}, current = {stateNode: {}}, old = {stateNode: {current}};
  element.__reactFiber$test = {stateNode: element, return: old};
  assert.throws(() => committedReactFiber(element), /not in the committed/);
});
