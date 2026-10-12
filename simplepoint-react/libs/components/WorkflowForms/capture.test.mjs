import assert from 'node:assert/strict';
import test from 'node:test';
import {captureWorkflowVariables} from './capture.ts';

const form = {definitionVersion: 1, formContract: {schemaVersion: 1, taskBindings: {}, variables: [
  {name: 'approved', label: 'Approved', type: 'boolean', scope: 'task', required: true},
  {name: 'roles', label: 'Roles', type: 'roles', scope: 'both', required: false},
]}};
const snapshot = changes => ({form, stage: 'task', value: {approved: false}, providerAvailable: false, providerValid: false, ...changes});

test('an invalid current draft blocks capture of its previously valid values', () => {
  assert.throws(() => captureWorkflowVariables(snapshot({error: 'Invalid JSON'}), form), /Invalid JSON/);
  assert.deepEqual(captureWorkflowVariables(snapshot({value: {approved: true}}), form), {approved: true});
});
test('captured values are independent of later caller edits', () => {
  const value = {approved: true, roles: ['tenant.approver']}, captured = captureWorkflowVariables(snapshot({value}), form);
  value.roles.push('other'); value.approved = false;
  assert.deepEqual(captured, {approved: true, roles: ['tenant.approver']});
});
test('submission rechecks required values, types and current stage ownership', () => {
  for (const value of [{}, {approved: 'true'}, {approved: true, unknown: 'x'}]) {
    assert.throws(() => captureWorkflowVariables(snapshot({value}), form));
  }
  assert.throws(() => captureWorkflowVariables(snapshot({stage: 'start'}), form), /当前阶段/);
});
test('a different form owner and unavailable or invalid bound provider cannot be submitted', () => {
  assert.throws(() => captureWorkflowVariables(snapshot(), {...form}), /已切换/);
  const bound = {...form, binding: {provider: 'engine', formId: 'form1', version: 1, variableMappings: {}}};
  assert.throws(() => captureWorkflowVariables(snapshot({form: bound}), bound), /尚未接入/);
  assert.throws(() => captureWorkflowVariables(snapshot({form: bound, providerAvailable: true}), bound), /校验/);
  assert.deepEqual(captureWorkflowVariables(snapshot({form: bound, providerAvailable: true, providerValid: true}), bound), {approved: false});
});
test('ordinary and provider values obey the same size, depth and serialization limits as JSON mode', () => {
  const cyclic = {}; cyclic.self = cyclic;
  assert.throws(() => captureWorkflowVariables(snapshot({value: cyclic}), form), /嵌套/);
  assert.throws(() => captureWorkflowVariables(snapshot({value: {approved: true, big: 1n}}), form), /可序列化/);
  assert.throws(() => captureWorkflowVariables(snapshot({value: {approved: true, nonfinite: Infinity}}), form), /有限/);
  assert.throws(() => captureWorkflowVariables(snapshot({value: {approved: true, large: 'x'.repeat(128 * 1024)}}), form), /大小/);
});

test('ordinary forms cannot silently submit invisible fields after the JSON entry is removed', () => {
  const empty = {...form, formContract: {...form.formContract, variables: []}};
  assert.deepEqual(captureWorkflowVariables(snapshot({form: empty, value: {}}), empty), {});
  assert.throws(() => captureWorkflowVariables(snapshot({form: empty, value: {hidden: 'value'}}), empty), /未配置的表单字段/);
});
