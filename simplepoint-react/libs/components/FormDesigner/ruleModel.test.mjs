import assert from 'node:assert/strict';
import test from 'node:test';
import {applyRuleEdit, compileCondition, editRule, moveRule, newRule, removeRule, ruleCondition, ruleQueryContract, ruleTargets} from './ruleModel.ts';

const field = (fieldId, type = 'STRING') => ({fieldId, storageCode: fieldId, label: fieldId, type, storageMode: 'STORED', storageRequired: false,
  submitRequired: false, length: type === 'STRING' ? 255 : null, choices: []});
const node = (componentId, fieldId) => ({componentId, componentType: fieldId ? 'field.text' : 'layout.form', componentVersion: '1',
  fieldId, readOnly: false, properties: {}, children: [], columnSpan: 24});
const document = () => ({contractVersion: '1', fields: [field('name'), field('amount', 'DECIMAL'), field('timestamp', 'DATETIME')], indexes: [], rules: [],
  scenes: [{sceneId: 'main', name: 'Main', type: 'MAIN', root: {...node('root'), children: [node('name-input', 'name')]}},
    {sceneId: 'dialog', name: 'Details', type: 'DIALOG', root: {...node('dialog-root'), children: [node('amount-input', 'amount')]}}]});
const fail = (action, code) => assert.throws(action, reason => reason.code === code);

test('platform query tree bridges all groups and typed set literals without numerical coercion', () => {
  const doc = document(), query = {op: 'and', children: [
    {op: 'in', field: 'amount', value: ['9223372036854775807', '1.000000000000000001']},
    {op: 'not', children: [{op: 'or', children: [{op: 'is:null', field: 'name'}, {op: 'equals', field: 'name', value: '東京'}]}]},
  ]};
  const condition = compileCondition(query, doc);
  assert.equal(condition.children[0].values[0].value, '9223372036854775807');
  assert.equal(condition.children[0].values[1].value, '1.000000000000000001');
  assert.deepEqual(ruleCondition(condition), query);
  // Rule thresholds need not fit a field's storage precision: a limit can lie outside stored values.
  assert.equal(compileCondition({field: 'amount', op: 'than:greater', value: '1e100'}, doc).values[0].value.length, 101);
});

test('operator capability and limits reject incomplete or malformed drafts', () => {
  const doc = document();
  assert.equal(ruleQueryContract(doc).fields.name.operators.includes('than:greater'), false);
  assert.equal(ruleQueryContract(doc).fields.timestamp.operators.includes('than:greater'), true);
  for (const query of [{op: 'and', children: []}, {op: 'not', children: [{op: 'is:null', field: 'name'}, {op: 'is:null', field: 'name'}]},
    {op: 'equals', field: 'missing', value: 'x'}, {op: 'than:greater', field: 'name', value: 'A'}]) fail(() => compileCondition(query, doc), 'condition');
  for (const query of [{op: 'equals', field: 'amount'}, {op: 'in', field: 'amount', value: '1'},
    {op: 'equals', field: 'amount', value: '1e999999'}, {op: 'equals', field: 'timestamp', value: '2026-02-29T12:00:00Z'}]) fail(() => compileCondition(query, doc), 'literal');
  fail(() => compileCondition({op: 'in', field: 'name', value: Array(101).fill('A')}, doc), 'limit');
  fail(() => compileCondition({op: 'and', children: Array(32).fill({op: 'is:null', field: 'name'})}, doc), 'limit');
  let deep = {op: 'is:null', field: 'name'}; for (let i = 0; i < 4; i++) deep = {op: 'not', children: [deep]};
  fail(() => compileCondition(deep, doc), 'limit');
});

test('cross-scene targets are explicit and required effects are restricted to stored inputs', () => {
  const doc = document();
  assert.deepEqual(ruleTargets(doc, 'REQUIRE').map(target => target.id), ['name-input', 'amount-input']);
  assert.ok(ruleTargets(doc, 'HIDE').some(target => target.id === 'dialog-root'));
  const edit = {...newRule(doc), effect: 'REQUIRE', targetComponentId: 'amount-input'};
  const next = applyRuleEdit(doc, undefined, edit);
  assert.equal(doc.rules.length, 0); assert.equal(next.rules[0].targetComponentId, 'amount-input');
  fail(() => applyRuleEdit(doc, undefined, {...edit, targetComponentId: 'root'}), 'target');
  doc.fields[1].storageMode = 'TRANSIENT';
  fail(() => applyRuleEdit(doc, undefined, edit), 'target');
});

test('editing, precedence moves and removal preserve identity and detect stale overwrites', () => {
  let doc = document();
  doc = applyRuleEdit(doc, undefined, {...newRule(doc), targetComponentId: 'name-input'});
  const first = structuredClone(doc.rules[0]);
  doc = applyRuleEdit(doc, undefined, {...newRule(doc), targetComponentId: 'name-input', effect: 'HIDE'});
  const second = doc.rules[1], moved = moveRule(doc, second, -1);
  assert.deepEqual(moved.rules.map(rule => rule.ruleId), [second.ruleId, first.ruleId]);
  assert.equal(doc.rules[0].ruleId, first.ruleId);
  const updated = applyRuleEdit(moved, first, {...editRule(first), effect: 'DISABLE', ruleId: 'untrusted-replacement'});
  assert.equal(updated.rules[1].ruleId, first.ruleId);
  fail(() => removeRule(updated, first), 'conflict');
  assert.equal(removeRule(updated, updated.rules[1]).rules.length, 1);
  const full = {...doc, rules: Array.from({length: 500}, (_, i) => ({...first, ruleId: `rule-${i}`}))};
  fail(() => applyRuleEdit(full, undefined, {...newRule(full), targetComponentId: 'name-input'}), 'limit');
});
