import {clearLiveReferences} from './liveReferenceValues.ts';
import assert from 'node:assert/strict';
import test from 'node:test';
import {liveReferenceKey, liveSceneFields, mergeLiveReferences, planLiveReferences,
  referenceDisplayValue, validateLiveBatch} from './liveReferenceValues.ts';

const first = '11111111-1111-1111-1111-111111111111', second = '22222222-2222-2222-2222-222222222222';
const field = (fieldId, type = 'STRING') => ({fieldId, type, label: fieldId, choices: []});
const fields = relationId => [{...field('reference'), reference: {relationId, mappings: [
  {mappingId: 'live', destinationFieldId: 'amount', mode: 'LIVE'},
]}}, {...field('amount', 'DECIMAL'), referenceValueMode: 'LIVE'}, field('memo')];
const document = {fields: fields('customer'), details: [{detailId: 'lines', fields: fields('product')}]};
const values = () => ({reference: first, amount: 'stale-root', memo: '客户・日本', lines: [
  {id: 'row1', version: 3, values: {reference: first, amount: 'stale-row', memo: 'mine'}},
  {values: {reference: second, amount: 'stale-new'}}, {values: {reference: first}},
]});
const view = (id, amount) => ({targetRecordId: id, targetVersion: 1, values: {amount: {kind: 'DECIMAL', value: amount}}});

test('groups distinct selections per relation while preserving independent row positions', () => {
  const input = values(), plan = planLiveReferences(document, input);
  assert.deepEqual(plan.queries, [{relationId: 'customer', ids: [first]}, {relationId: 'product', ids: [first, second]}]);
  const result = mergeLiveReferences(input, plan, new Map([
    [liveReferenceKey('customer', first), view(first, '9007199254740993.12')],
    [liveReferenceKey('product', first), view(first, '1.25')],
    [liveReferenceKey('product', second), view(second, '2.50')],
  ]));
  assert.equal(result.amount, '9007199254740993.12');
  assert.deepEqual(result.lines.map(row => row.values.amount), ['1.25', '2.50', '1.25']);
  assert.deepEqual(result.lines[0], {id: 'row1', version: 3, values: {reference: first, amount: '1.25', memo: 'mine'}});
  assert.deepEqual(input, values());
});

test('clearing, missing grants, loading and a new selection cannot display the old value', () => {
  const input = values();
  const clear = mergeLiveReferences(input, planLiveReferences(document, input));
  assert.equal(clear.amount, null);
  assert.deepEqual(clear.lines.map(row => row.values.amount), [null, null, null]);
  const changed = {...input, reference: second, lines: [{values: {reference: null, amount: 'old'}}]};
  const stale = new Map([[liveReferenceKey('customer', first), view(first, '99')]]);
  const result = mergeLiveReferences(changed, planLiveReferences(document, changed), stale);
  assert.equal(result.amount, null);
  assert.equal(result.lines[0].values.amount, null);
  assert.equal(result.memo, '客户・日本');
});

test('row reorder remaps display results by current reference rather than the old index', () => {
  const input = values();
  input.lines.reverse();
  const result = mergeLiveReferences(input, planLiveReferences(document, input), new Map([
    [liveReferenceKey('product', first), view(first, '10')], [liveReferenceKey('product', second), view(second, '20')],
  ]));
  assert.deepEqual(result.lines.map(row => row.values.amount), ['10', '20', '10']);
  assert.equal(result.lines[2].id, 'row1');
});

test('scene selection includes visible destinations even if the reference control is in another scene', () => {
  const scene = {sceneId: 'main', root: {properties: {}, children: [{fieldId: 'amount', properties: {}, children: []}]}};
  const source = {...document, scenes: [scene]};
  const visible = liveSceneFields(source, 'main');
  assert.deepEqual([...visible], ['amount']);
  assert.deepEqual(planLiveReferences(source, values(), visible).queries, [{relationId: 'customer', ids: [first]}]);
  assert.deepEqual(planLiveReferences(source, values(), new Set()).queries, []);
  const invalid = {...values(), reference: '***'};
  assert.deepEqual(planLiveReferences(source, invalid, visible).queries, []);
  assert.equal(mergeLiveReferences(invalid, planLiveReferences(source, invalid, visible)).amount, null);
});

test('batch responses reject wrong identities, duplicates, stale unavailable values and invalid versions', () => {
  assert.deepEqual(validateLiveBatch([first], [view(first, '1')]), [view(first, '1')]);
  assert.throws(() => validateLiveBatch([first], [view(second, '1')]));
  assert.throws(() => validateLiveBatch([first, second], [view(first, '1'), view(first, '1')]));
  assert.throws(() => validateLiveBatch([first], [{...view(first, '1'), targetVersion: null}]));
  assert.throws(() => validateLiveBatch([first], [{...view(first, '1'), targetVersion: Number.MAX_SAFE_INTEGER + 1}]));
  assert.throws(() => validateLiveBatch([first], []));
});

test('typed projections preserve exact numbers and reject type confusion', () => {
  assert.equal(referenceDisplayValue(field('value', 'INT64'), {kind: 'INTEGER', value: '9007199254740993'}), '9007199254740993');
  assert.equal(referenceDisplayValue(field('value', 'INT32'), {kind: 'INTEGER', value: '42'}), 42);
  assert.equal(referenceDisplayValue(field('value', 'BOOLEAN'), {kind: 'BOOLEAN', value: 'false'}), false);
  assert.throws(() => referenceDisplayValue(field('value', 'INT32'), {kind: 'INTEGER', value: '9007199254740993'}));
  assert.throws(() => referenceDisplayValue(field('value', 'DECIMAL'), {kind: 'INTEGER', value: '1'}));
  assert.throws(() => referenceDisplayValue(field('value', 'BOOLEAN'), {kind: 'BOOLEAN', value: 'yes'}));
});


test('invalid read plans cannot display stale live copies or mutate oversized input collections', () => {
  const document = {fields: [{fieldId: 'live', referenceValueMode: 'LIVE'}], details: [{detailId: 'lines',
    fields: [{fieldId: 'live', referenceValueMode: 'LIVE'}]}]};
  const input = {live: 'stale', ordinary: 'keep', lines: Array.from({length: 41}, () => ({values: {live: 'stale'}}))};
  const cleared = clearLiveReferences(document, input);
  assert.equal(cleared.live, null);
  assert.equal(cleared.ordinary, 'keep');
  assert.deepEqual(cleared.lines, []);
  assert.equal(input.lines.length, 41);
  assert.equal(input.lines[0].values.live, 'stale');
});
