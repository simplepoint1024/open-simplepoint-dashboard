import assert from 'node:assert/strict';
import test from 'node:test';
import {createActionRunner, isFormValidationError} from './actionRunner.ts';

test('rapid repeated clicks cannot submit the same operation twice', async () => {
  const run = createActionRunner();
  let calls = 0;
  let release;
  const action = () => {calls++; return new Promise(resolve => {release = resolve;});};
  const first = run('save', action, assert.fail);
  assert.deepEqual(await run('save', action, assert.fail), {status: 'busy'});
  assert.equal(calls, 1);
  assert.deepEqual(await run('other', async () => 2, assert.fail), {status: 'success', value: 2});
  release(1);
  assert.deepEqual(await first, {status: 'success', value: 1});
});
test('failure reports once, releases its lock, and permits explicit retry', async () => {
  const run = createActionRunner();
  const errors = [];
  assert.deepEqual(await run('save', async () => {throw new Error('offline');}, error => errors.push(error.message)), {status: 'failed'});
  assert.deepEqual(errors, ['offline']);
  assert.deepEqual(await run('save', async () => true, assert.fail), {status: 'success', value: true});
});
test('only actual Ant Form field validation errors suppress the generic toast', () => {
  assert.equal(isFormValidationError({errorFields: [{name: ['name'], errors: ['required']}]}), true);
  for (const value of [null, new Error('offline'), {errorFields: []}, {errorFields: 'error'}]) {
    assert.equal(isFormValidationError(value), false);
  }
});
