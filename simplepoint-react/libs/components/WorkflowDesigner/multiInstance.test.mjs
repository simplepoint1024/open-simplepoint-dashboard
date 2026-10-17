import assert from 'node:assert/strict';
import test from 'node:test';
import {MULTI_INSTANCE_TYPE, multiInstanceIssues, iterationLocals, multiInstanceCounters} from './multiInstance.ts';
import {reservedVariable} from './contracts.ts';

const variables = [{name: 'reviewers', type: 'array', kind: 'users', label: 'Reviewers'},
  {name: 'groups', type: 'array', kind: 'roles', label: 'Groups'}, {name: 'amount', type: 'number', label: 'Amount'}];
const loop = patch => ({$type: MULTI_INSTANCE_TYPE, ...patch});

test('native multi-instance requires exactly one bounded count or collection source', () => {
  for (const count of ['1', '50', '+2', '${amount}']) assert.deepEqual(multiInstanceIssues(loop({loopCardinality: {body: count}}), variables), []);
  for (const count of ['0', '51', '-1', '1.5', 'NaN', '999999999999999999999']) {
    assert.ok(multiInstanceIssues(loop({loopCardinality: {body: count}}), variables).includes('multiCount'), count);
  }
  assert.deepEqual(multiInstanceIssues(loop({}), variables), ['multiSource']);
  assert.ok(multiInstanceIssues(loop({collection: 'reviewers', loopCardinality: {body: '2'}}), variables).includes('multiSource'));
});

test('collection kinds and iteration-local scopes cannot disguise unrelated or privileged variables', () => {
  for (const collection of ['reviewers', '${reviewers}', '#{ reviewers }', 'groups']) {
    assert.deepEqual(multiInstanceIssues(loop({collection, elementVariable: 'item'}), variables), []);
  }
  for (const collection of ['amount', 'missing', 'broken name']) {
    assert.ok(multiInstanceIssues(loop({collection}), variables).includes('multiCollection'));
  }
  for (const elementVariable of ['initiator', 'nrOfInstances', 'loopCounter', 'reviewers', 'amount', 'a-b']) {
    assert.ok(multiInstanceIssues(loop({collection: 'reviewers', elementVariable}), variables).includes('multiItem'));
  }
  assert.ok(multiInstanceIssues(loop({collection: 'reviewers', elementVariable: 'item', elementIndexVariable: 'item'}), variables).includes('multiItem'));
  assert.ok(multiInstanceIssues(loop({collection: 'reviewers', elementVariable: 'item'}), variables, ['item']).includes('multiItem'));
  assert.ok(multiInstanceIssues(loop({loopCardinality: {body: '2'}, elementVariable: 'item'}), []).includes('multiItem'));
  assert.deepEqual(multiInstanceIssues(loop({collection: 'legacyUsers', elementVariable: 'person'}), []), [], 'empty legacy contracts remain usable');
});

test('completion and collection expressions do not open an executable surface', () => {
  const source = {collection: 'reviewers'};
  for (const body of ['${nrOfCompletedInstances >= 1}', '${nrOfCompletedInstances / nrOfInstances >= 0.5}', '${true}']) {
    assert.deepEqual(multiInstanceIssues(loop({...source, completionCondition: {body}}), variables), []);
  }
  for (const body of ['yes', '${1}', '${null}', '${"true"}']) {
    assert.ok(multiInstanceIssues(loop({...source, completionCondition: {body}}), variables).includes('multiCompletion'));
  }
  for (const collection of ['${execution.variables}', '${users.fetch()}', '${users["class"]}']) {
    assert.ok(multiInstanceIssues(loop({collection}), variables).includes('multiExpression'));
  }
});

test('nested iteration variables preserve role versus user meaning without inventing unknown identities', () => {
  const outer = {loopCharacteristics: loop({collection: 'groups', elementVariable: 'group', elementIndexVariable: 'position'})};
  const inner = {$parent: outer, loopCharacteristics: loop({collection: 'reviewers', elementVariable: 'person'})};
  assert.deepEqual(iterationLocals(inner, variables, 'Current').map(({name, kind, type}) => ({name, kind, type})), [
    {name: 'person', kind: 'user', type: 'string'}, {name: 'group', kind: 'role', type: 'string'},
    {name: 'position', kind: undefined, type: 'number'},
  ]);
  outer.$parent = inner;
  assert.equal(iterationLocals(inner, variables, 'Current').length, 3, 'defensive parent cycle does not hang');
  assert.equal(iterationLocals({loopCharacteristics: loop({collection: '${dynamic}', elementVariable: 'item'})}, [], '')[0].kind, undefined);
  for (const counter of multiInstanceCounters({total: 'Total', active: 'Active', completed: 'Done', index: 'Index'})) {
    assert.equal(counter.readOnly, true); assert.equal(reservedVariable(counter.name), true);
  }
});
