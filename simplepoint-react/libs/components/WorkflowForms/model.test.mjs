import assert from 'node:assert/strict';
import {test} from 'node:test';
import {parseVariables, runtimeSchema, variableSchema, variablesIssue} from './model.ts';

const variables = [
  {name: 'approver', label: 'Approver', type: 'user', scope: 'start', required: true},
  {name: 'approved', label: 'Approved', type: 'boolean', scope: 'task', required: true},
  {name: 'roles', label: 'Roles', type: 'roles', scope: 'both', required: false},
];

test('runtime forms filter stage-owned fields and use shared directory widgets', () => {
  const schema = runtimeSchema(variables, 'start');
  assert.deepEqual(schema.required, ['approver']);
  assert.equal(schema.properties.approved, undefined);
  assert.equal(schema.properties.approver['x-ui'].widget, 'TenantDirectory');
  assert.deepEqual(variableSchema(variables[2])['x-ui'].options, {kind: 'roles', multiple: true});
});

test('JSON mode retains scalar types and refuses non-objects or unbounded input', () => {
  assert.deepEqual(parseVariables('{"approved":false,"amount":1.5}'), {approved: false, amount: 1.5});
  for (const value of ['null', '[]', 'true', 'broken']) assert.throws(() => parseVariables(value));
  for (const value of ['{"amount":1e999}', '{"task":"override"}', '{"execution":"override"}']) assert.throws(() => parseVariables(value));
  assert.throws(() => parseVariables(JSON.stringify(Object.fromEntries(Array.from({length: 101}, (_, index) => ['v' + index, index])))));
});

test('typed variable validation accepts false and rejects missing, forged-stage and duplicate-directory values', () => {
  assert.equal(variablesIssue(variables, {approved: false}, 'task'), undefined);
  assert.ok(variablesIssue(variables, {}, 'start'));
  assert.ok(variablesIssue(variables, {approver: 'someone'}, 'task'));
  assert.ok(variablesIssue(variables, {approved: true, roles: ['a', 'a']}, 'task'));
});

test('role values accept tenant authorities, not just ASCII user IDs, without ambiguous delimiters', () => {
  assert.equal(variablesIssue(variables, {approved: false, roles: ['部门/审核', 'review (operations)']}, 'task'), undefined);
  for (const role of ['role,other', ' leading', 'trailing ', 'role\tcontrol', 'x'.repeat(101)]) {
    assert.ok(variablesIssue(variables, {approved: false, roles: [role]}, 'task'));
  }
  assert.ok(variablesIssue(variables, {approver: 'not a user id'}, 'start'));
});

test('optional fields still validate explicitly provided values; omission is not an invalid scalar', () => {
  const optional = [{name: 'flag', label: 'Flag', type: 'boolean', scope: 'both', required: false}];
  assert.equal(variablesIssue(optional, {}, 'start'), undefined);
  assert.equal(variablesIssue(optional, {flag: false}, 'start'), undefined);
  assert.ok(variablesIssue(optional, {flag: ''}, 'start'));
});
