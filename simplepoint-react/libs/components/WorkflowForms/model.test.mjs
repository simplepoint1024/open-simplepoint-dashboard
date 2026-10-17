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
  assert.equal(schema.properties.approver['x-ui'].widget, 'UserSelect');
  assert.deepEqual(variableSchema(variables[2])['x-ui'].options, {multiple: true});
  assert.equal(variableSchema(variables[2])['x-ui'].widget, 'RoleSelect');
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


test('exact decimal inputs never coerce large integers or currency into JavaScript Number', () => {
  const decimal = {name: 'amount', label: 'Amount', type: 'decimal', scope: 'both', required: true};
  const exact = '9007199254740993.12345678901234567890';
  assert.equal(variableSchema(decimal).type, 'string');
  assert.equal(variablesIssue([decimal], {amount: exact}, 'start'), undefined);
  for (const invalid of [1.25, 1, '1e2', '01', '-', ' 1', '9'.repeat(39), '0.' + '0'.repeat(38) + '1']) {
    assert.ok(variablesIssue([decimal], {amount: invalid}, 'start'));
  }
});
