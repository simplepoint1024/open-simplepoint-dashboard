import assert from 'node:assert/strict';
import {test} from 'node:test';
import {componentResource} from './resource.ts';
test('shares an in-flight import and caches successful code', async () => {
  let calls = 0, finish;
  const component = () => null;
  const resource = componentResource(() => {calls++; return new Promise(resolve => {finish = resolve;});});
  const first = resource.load(), second = resource.load();
  assert.equal(first, second); await Promise.resolve(); assert.equal(calls, 1);
  finish({default: component}); assert.equal(await first, component);
  assert.equal(await resource.load(), component); assert.equal(calls, 1);
});
test('a rejected import does not poison the next explicit retry', async () => {
  let calls = 0;
  const component = () => null;
  const resource = componentResource(async () => {if (++calls === 1) throw new Error('network'); return {default: component};});
  await assert.rejects(resource.load(), /network/); assert.equal(resource.current(), undefined);
  assert.equal(await resource.load(), component); assert.equal(calls, 2);
});
