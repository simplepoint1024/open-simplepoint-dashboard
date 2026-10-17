import assert from 'node:assert/strict';
import test from 'node:test';
import {standardLoopIssues} from './standardLoop.ts';
import {STANDARD_LOOP_TYPE, loopConditionValue} from './loopCharacteristics.ts';

const loop = patch => ({$type: STANDARD_LOOP_TYPE, loopMaximum: 3, ...patch});

test('standard loops always have a bounded whole-number maximum, independent of conditions', () => {
  for (const loopMaximum of [1, 3, 50]) assert.deepEqual(standardLoopIssues(loop({loopMaximum})), []);
  for (const loopMaximum of [undefined, null, 0, -1, 51, 1.2, NaN, Infinity, '2', '${count}']) {
    assert.ok(standardLoopIssues(loop({loopMaximum})).includes('loopMaximum'), String(loopMaximum));
  }
  assert.deepEqual(standardLoopIssues(undefined), []);
  assert.deepEqual(standardLoopIssues({$type: 'bpmn:MultiInstanceLoopCharacteristics'}), []);
});

test('standard loop timing and condition structure reject invalid literals and unsafe calls', () => {
  for (const testBefore of [undefined, false, true]) assert.deepEqual(standardLoopIssues(loop({testBefore})), []);
  assert.ok(standardLoopIssues(loop({testBefore: 'false'})).includes('loopTest'));
  for (const body of ['${approved}', '${true}', '#{false}', '${amount > 2}']) {
    assert.deepEqual(standardLoopIssues(loop({loopCondition: {body}})), [], body);
  }
  // Full EL grammar is checked by the server parser, not the visual-subset parser.
  for (const body of ['', 'yes', '${1}', '${null}', '${"true"}', '${approved', '${execution.variables}', '${user.fetch()}']) {
    assert.ok(standardLoopIssues(loop({loopCondition: {body}})).includes('loopCondition'), body);
  }
});

test('draft invalidation compares the condition of the actual execution kind', () => {
  assert.equal(loopConditionValue(loop({loopCondition: {body: '${repeat}'}})), '${repeat}');
  assert.equal(loopConditionValue({$type: 'bpmn:MultiInstanceLoopCharacteristics', completionCondition: {body: '${done}'}}), '${done}');
  assert.equal(loopConditionValue(undefined), '');
});
