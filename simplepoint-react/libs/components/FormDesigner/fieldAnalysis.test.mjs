import test from 'node:test';
import assert from 'node:assert/strict';
import {affectedFields, designFields, fieldDependencies, fieldKey, fieldUsages, replaceDesignField} from './fieldAnalysis.ts';
import {designIssues} from './calculationDiagnostics.ts';
import {calculationSampleFields, calculationTrialInputs, sampleObjectSchema} from './calculationTrialModel.ts';
import {publicationChanges} from './definitionChanges.ts';
const field = (fieldId, expression) => ({fieldId, label: fieldId, type: 'DECIMAL', precision: 30, scale: 8,
  storageMode: expression ? 'COMPUTED' : 'STORED', storageRequired: false, submitRequired: false, choices: [],
  ...(expression ? {calculation: {languageVersion: '1', expression}} : {})});
const node = (id, fieldId) => ({componentId: id, fieldId, componentType: 'field.number', children: [], properties: {}});
const document = () => ({contractVersion: '1', fields: [field('rate'), field('total', 'SUM([lines.amount])'), field('double', '[total]*2')],
  indexes: [], rules: [], scenes: [
    {sceneId: 'main', name: '主页面', root: {...node('root'), children: [node('rate-main', 'rate'), {...node('detail'), properties: {detailId: {value: 'lines'}}}]}},
    {sceneId: 'dialog', name: '弹窗', root: {...node('dialog-root'), children: [node('rate-dialog', 'rate')]}},
  ], details: [{detailId: 'lines', label: '明细', fields: [field('quantity'), field('amount', '[quantity]*[parent.rate]')], indexes: []}]});
const functions = [{name: 'SUM', minArguments: 1, maxArguments: 256}];

test('main/detail dependency closure preserves stable identities and identifies actual cycles', () => {
  const source = document();
  assert.deepEqual(affectedFields(source, {scope: 'parent', fieldId: 'rate'}).map(item => item.fieldId), ['total', 'double', 'amount']);
  assert.deepEqual(designIssues(source, functions), []);
  source.fields[0] = field('rate', '[total]');
  const graph = fieldDependencies(source);
  assert(graph.cyclic.has(fieldKey({scope: 'parent', fieldId: 'total'})));
  assert(graph.cyclic.has(fieldKey({scope: 'lines', fieldId: 'amount'})));
  assert(!graph.cyclic.has(fieldKey({scope: 'parent', fieldId: 'double'})), 'a downstream consumer is not itself cyclic');
  assert.equal(designIssues(source, functions).filter(issue => issue.code === 'DEPENDENCY_CYCLE').length, 3);
});
test('invalid drafts remain diagnosable and shared-field uses span scenes', () => {
  const source = document(); source.fields[1].calculation.expression = 'UNKNOWN([rate])';
  const issues = designIssues(source, functions);
  assert.equal(issues[0].fieldId, 'total'); assert.equal(issues[0].code, 'UNKNOWN_FUNCTION');
  assert.deepEqual(fieldUsages(source, {scope: 'parent', fieldId: 'rate'}).map(item => item.sceneId), ['main', 'dialog']);
  assert.equal(fieldUsages(source, {scope: 'lines', fieldId: 'amount'})[0].componentId, 'detail');
});
test('intersecting dependency cycles include every participant, excluding downstream consumers', () => {
  const source = document(); source.details = [];
  source.fields = [field('a', '[b]+[c]'), field('b', '[a]'), field('c', '[b]'), field('d', '[c]')];
  assert.deepEqual([...fieldDependencies(source).cyclic].sort(), ['a', 'b', 'c'].map(fieldId => fieldKey({scope: 'parent', fieldId})).sort());
});
test('configuration diagnostics include invalid ordinary field attributes without normalizing a draft', () => {
  const source = document(); source.fields[0].precision = 0;
  const before = structuredClone(source), issues = designIssues(source, functions);
  assert.equal(issues[0].fieldId, 'rate'); assert.equal(issues[0].code, 'FIELD_VALIDATION');
  assert.deepEqual(source, before);
});
test('local detail projection preserves siblings and supports unsaved trial metadata', () => {
  const source = document(), original = structuredClone(source);
  const next = replaceDesignField(source, 'lines', field('amount', '[quantity]+[parent.rate]'));
  assert.equal(next.details[0].fields[1].calculation.expression, '[quantity]+[parent.rate]');
  assert.deepEqual(source, original); assert.equal(next.fields, source.fields);
  assert.throws(() => replaceDesignField(source, 'missing', field('x')));
});
test('trial inputs strip forged outputs and preserve exact numeric strings, nulls and row order', () => {
  const source = document(), exact = '12345678901234567890.12345678';
  const result = calculationTrialInputs(source, {values: {rate: exact, total: 'forged', unknown: 'x'},
    details: {lines: [{quantity: null, amount: 'forged'}, {quantity: '2'}], other: [{}]}});
  assert.deepEqual(result, {values: {rate: exact}, details: {lines: [{quantity: null}, {quantity: '2'}]}});
  assert.equal(sampleObjectSchema(source.fields).properties.rate['x-decimal'].precision, 30);
  assert.equal(Object.hasOwn(sampleObjectSchema(source.fields).properties, 'total'), false);
  assert.throws(() => calculationTrialInputs(source, {details: {lines: Array(41).fill({})}}));
});
test('sample editors include dependencies of every formula and mandatory inputs, excluding unrelated optional fields', () => {
  const source = document();
  source.fields.push(field('unrelated'), {...field('mandatory'), storageRequired: true});
  source.details[0].fields.push(field('unused'));
  const inputs = calculationSampleFields(source);
  assert.deepEqual(inputs.fields.map(item => item.fieldId), ['rate', 'mandatory']);
  assert.deepEqual(inputs.details[0].fields.map(item => item.fieldId), ['quantity']);
  assert.deepEqual(calculationTrialInputs({...source, ...inputs}, {values: {rate: '1', unrelated: 'old invalid input'},
    details: {lines: [{quantity: '2', unused: 'old invalid input'}]}}),
  {values: {rate: '1'}, details: {lines: [{quantity: '2'}]}});
});
test('publication differences distinguish formula, property and detail changes by identity', () => {
  const before = document(), after = structuredClone(before);
  after.fields[0].label = '新名称'; after.fields[1].calculation.expression = 'SUM([lines.amount])+1';
  after.details[0].fields.push(field('extra')); after.fields.pop();
  assert.deepEqual(publicationChanges(before, after).map(change => change.kind), ['attributes', 'formula', 'added', 'removed']);
  assert.equal(publicationChanges(undefined, after).length, designFields(after).length);
});
