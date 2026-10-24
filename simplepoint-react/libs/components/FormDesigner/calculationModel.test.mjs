import assert from 'node:assert/strict';
import test from 'node:test';
import {formulaReferences, parseFormula} from '@simplepoint/calculation';
import {calculationFields, remapCalculation} from './calculationModel.ts';
import {cloneDetail} from '../FormComponents/Detail/model.ts';

const field = (fieldId, expression) => ({fieldId, storageCode: fieldId, label: `标签 ${fieldId}`, storageLabel: fieldId,
  type: 'DECIMAL', precision: 18, scale: 2, storageMode: expression ? 'COMPUTED' : 'STORED', storageRequired: false,
  submitRequired: false, choices: [], ...(expression ? {calculation: {languageVersion: '1', expression}} : {})});
const document = () => ({contractVersion: '1', fields: [field('total', 'SUM([lines.amount])'), field('rate')], indexes: [], scenes: [], rules: [],
  details: [{detailId: 'lines', label: '报销明细', storageCode: 'lines', fields: [field('price'), field('amount', '[price]*[parent.rate]')], indexes: []}]});

test('formula palettes distinguish local, parent and collection identities without mutable storage names', () => {
  const model = document();
  assert.deepEqual(calculationFields(model, 'parent', 'total').map(value => value.id), ['rate', 'lines.price', 'lines.amount']);
  assert.deepEqual(calculationFields(model, 'lines', 'amount').map(value => value.id), ['price', 'parent.total', 'parent.rate']);
  model.fields[1].storageCode = 'renamed';
  assert.equal(calculationFields(model, 'parent', 'total')[0].id, 'rate');
});

test('AST remapping preserves text constants and unselected external references', () => {
  const original = field('formula', 'CONCAT([a],"[a]",[external])');
  const result = remapCalculation(original, new Map([['a', 'new-a']]));
  assert.deepEqual(formulaReferences(parseFormula(result.calculation.expression)), new Set(['external', 'new-a']));
  assert.equal(parseFormula(result.calculation.expression).children[1].text, '[a]');
  assert.equal(original.calculation.expression, 'CONCAT([a],"[a]",[external])');
});

test('detail clones rewrite local and copied parent formulas and return aggregate reference mappings', () => {
  const model = document(), source = structuredClone(model);
  const component = {properties: {detailId: {kind: 'STRING', value: 'lines'}}};
  let sequence = 0;
  const mappings = cloneDetail(component, () => `new-${++sequence}`, model, source, new Map([['rate', 'new-rate']]));
  const copied = model.details[1];
  assert.equal(copied.detailId, 'new-1');
  assert.deepEqual(formulaReferences(parseFormula(copied.fields[1].calculation.expression)), new Set(['parent.new-rate', 'new-2']));
  assert.equal(mappings.get('lines.amount'), 'new-1.new-3');
  assert.equal(source.details.length, 1);
  assert.equal(source.details[0].fields[1].calculation.expression, '[price]*[parent.rate]');
});
