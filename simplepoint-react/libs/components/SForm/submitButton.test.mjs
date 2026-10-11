import assert from 'node:assert/strict';
import {test} from 'node:test';
import {submitButtonState} from './submitButton.ts';

test('submit label accepts the RJSF 6 normalized template contract and legacy root syntax', () => {
  assert.equal(submitButtonState({'ui:options': {submitButtonOptions: {submitText: '通过'}}}, {}, '提交').text, '通过');
  assert.equal(submitButtonState({'ui:submitButtonOptions': {submitText: '确认驳回'}}, {}, '提交').text, '确认驳回');
  assert.equal(submitButtonState(undefined, undefined, '提交').text, '提交');
});

test('engine disabled, pending input preparation, loading and norender options are respected', () => {
  assert.deepEqual(submitButtonState({'ui:options': {submitButtonOptions: {props: {disabled: true}, norender: true}}}, {}, '提交'),
    {text: '提交', hidden: true, disabled: true, loading: false});
  assert.equal(submitButtonState({}, {submitDisabled: true}, '提交').disabled, true);
  assert.deepEqual(submitButtonState({}, {submitLoading: true}, '提交'),
    {text: '提交', hidden: false, disabled: true, loading: true});
});
