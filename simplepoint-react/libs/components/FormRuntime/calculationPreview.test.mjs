import assert from 'node:assert/strict';
import test from 'node:test';
import {calculationInputs, hasCalculatedFields, hasPreviewFields, mergeCalculationPreview} from './calculationPreview.ts';
import {fieldWriteAllowed} from './writePolicy.ts';
import {detailPayload} from '../FormComponents/Detail/payload.ts';

const document = {fields: [{fieldId: 'name'}, {fieldId: 'total', calculated: true}], details: [
  {detailId: 'lines', fields: [{fieldId: 'price'}, {fieldId: 'amount', calculated: true}]},
]};
const original = () => ({name: '漢字😀', total: 'old', lines: [{id: 'row', version: 4, values: {price: '9007199254740993.12', amount: 'old'}}]});

test('preview inputs and writes omit computed outputs at both aggregate levels', () => {
  const source = original(), result = calculationInputs(document, source);
  assert.equal(hasCalculatedFields(document), true);
  assert.deepEqual(result, {name: '漢字😀', lines: [{id: 'row', version: 4, values: {price: '9007199254740993.12'}}]});
  assert.equal(fieldWriteAllowed(document.fields[1]), false);
  assert.equal(fieldWriteAllowed(document.fields[1], 'DRAFT'), false);
  assert.deepEqual(detailPayload(document, source, 'DRAFT').lines[0].values, {price: '9007199254740993.12'});
  assert.deepEqual(source, original());
});

test('authorized previews merge only calculated outputs and preserve proposed ordering, identity and precision', () => {
  const source = original();
  const merged = mergeCalculationPreview(document, source, {
    values: {total: '9007199254740993.12', name: 'server value must not overwrite input'},
    details: {lines: [{amount: '9007199254740993.12', price: '0', id: 'not-a-committed-id'}]},
  });
  assert.equal(merged.total, '9007199254740993.12');
  assert.equal(merged.name, '漢字😀');
  assert.deepEqual(merged.lines[0], {id: 'row', version: 4, values: {price: '9007199254740993.12', amount: '9007199254740993.12'}});
  assert.deepEqual(source, original());
});

test('pending or revoked results clear stale computed values without creating rows or accepting mismatched responses', () => {
  const source = original();
  assert.equal(mergeCalculationPreview(document, source).total, null);
  assert.equal(mergeCalculationPreview(document, source).lines[0].values.amount, null);
  assert.equal(mergeCalculationPreview(document, {}).lines, undefined);
  assert.throws(() => mergeCalculationPreview(document, source, {values: {}, details: {lines: []}}), /mismatch/);
  assert.equal(mergeCalculationPreview(document, source, {values: {}, details: {lines: [{}]}}).total, null);
});

test('snapshot-only forms use authorized preview outputs without trusting submitted copy values', () => {
  const mapped = {fields: [{fieldId: 'copy', referenceValueMode: 'SERVER_SNAPSHOT'}], details: [
    {detailId: 'lines', fields: [{fieldId: 'copy', referenceValueMode: 'SERVER_SNAPSHOT'}]},
  ]};
  const value = {copy: 'old', lines: [{clientId: 'stable', values: {copy: 'old'}}]};
  assert.equal(hasPreviewFields(mapped), true);
  const input = calculationInputs(mapped, value);
  assert.deepEqual(input, {lines: [{clientId: 'stable', values: {}}]});
  const merged = mergeCalculationPreview(mapped, input, {values: {copy: 'server'}, details: {lines: [{copy: 'authorized'}]}});
  assert.equal(merged.copy, 'server');
  assert.deepEqual(merged.lines, [{clientId: 'stable', values: {copy: 'authorized'}}]);
  assert.equal(mergeCalculationPreview(mapped, value).lines[0].values.copy, null);
});
