import assert from 'node:assert/strict';
import test from 'node:test';
import {mappingEntry, applyMapping, mappingIntent, mappingDisplay, reconcileMappings, mappingDefaultIds} from './model.ts';
import {detailPayload, detailFormValues} from '../../FormComponents/Detail/payload.ts';
import {sceneDraft} from '../sceneState.ts';
const id = n => `aaaaaaaa-1111-2222-3333-${String(n).padStart(12, '0')}`;
const field = (fieldId, extra = {}) => ({fieldId, label: fieldId, type: 'STRING', choices: [], ...extra});
const fields = [field('customer', {reference: {relationId: 'customer-rel', mappingWritable: true, mappings: [
  {mappingId: 'snapshot', destinationFieldId: 'name', mode: 'SERVER_SNAPSHOT'},
  {mappingId: 'default', destinationFieldId: 'amount', mode: 'EDITABLE_DEFAULT'},
]}}), field('name', {referenceValueMode: 'SERVER_SNAPSHOT'}), field('amount', {type: 'DECIMAL', referenceValueMode: 'EDITABLE_DEFAULT'})];
const document = {fields, details: [{detailId: 'lines', fields}]};
const response = (targetRecordId = id(1)) => ({observation: {revision: 'a'.repeat(64), targetRecordId, targetVersion: targetRecordId ? 7 : null},
  snapshots: {name: targetRecordId ? {kind: 'STRING', value: '客户漢字😀'} : {kind: 'NULL'}},
  defaults: {amount: targetRecordId ? {kind: 'DECIMAL', value: '9007199254740993.12'} : {kind: 'NULL'}}});
const entry = (location = {fieldId: 'customer'}, target = id(1)) => mappingEntry(document, location, target, ['default'], response(target));

test('confirmation applies only chosen defaults; snapshots remain separate from editable payloads', () => {
  const values = {customer: id(2), amount: 'user edit'};
  const next = applyMapping(values, [], entry(), []);
  assert.deepEqual(next.values, {customer: id(1), amount: 'user edit'});
  assert.equal(mappingDisplay(next.values, next.draft).name, '客户漢字😀');
  assert.equal(next.values.name, undefined);
  assert.deepEqual(mappingIntent(next.draft, next.values).confirmations[0].applyDefaultMappingIds, []);
  assert.deepEqual(values, {customer: id(2), amount: 'user edit'});
});

test('selected defaults retain exact decimals and become ordinary inputs after a manual edit', () => {
  const next = applyMapping({}, [], entry(), ['default']);
  assert.equal(next.values.amount, '9007199254740993.12');
  const edited = {...next.values, amount: '42.00'};
  const draft = reconcileMappings(next.draft, edited);
  assert.deepEqual(mappingIntent(draft, edited).confirmations[0].applyDefaultMappingIds, []);
  assert.equal(mappingDisplay(edited, draft).amount, '42.00');
  assert.deepEqual(mappingIntent(draft, next.values).confirmations[0].applyDefaultMappingIds, [], 'typing the old value never restores consent');
});

test('detail confirmations follow saved and client row identities across prepend, reorder and delete', () => {
  let values = {lines: [{id: id(10), version: 3, values: {}}, {clientId: id(11), values: {}}]};
  let draft = [];
  for (const rowKey of [id(10), id(11)]) {
    const result = applyMapping(values, draft, entry({fieldId: 'customer', detailId: 'lines', rowKey}), ['default']);
    values = result.values; draft = result.draft;
  }
  const reordered = {lines: [{clientId: id(12), values: {}}, ...values.lines.toReversed()]};
  const confirmations = mappingIntent(draft, reordered).confirmations;
  assert.equal(confirmations[0].rowIndex, 1);
  assert.equal(confirmations[0].sourceRowId, undefined);
  assert.equal(confirmations[1].rowIndex, 2);
  assert.equal(confirmations[1].sourceRowId, id(10));
  const removed = {lines: reordered.lines.slice(0, 2)};
  assert.equal(mappingIntent(draft, removed).confirmations.length, 1);
  assert.equal(mappingDisplay(reordered, draft).lines[0].values.name, undefined);
  assert.equal(mappingDisplay(reordered, draft).lines[1].values.name, '客户漢字😀');
  assert.equal(detailFormValues(document, values).lines[1].clientId, id(11));
  const payload = detailPayload(document, mappingDisplay(reordered, draft));
  assert.equal(payload.lines[1].clientId, undefined);
  assert.equal(payload.lines[1].values.name, undefined);
  assert.equal(payload.lines[1].values.amount, '9007199254740993.12');
});

test('clear requires an explicit null observation and can retain user defaults', () => {
  const next = applyMapping({customer: id(1), amount: 'manual'}, [], entry(undefined, null), []);
  assert.equal(next.values.customer, null);
  assert.equal(next.values.amount, 'manual');
  assert.equal(mappingDisplay(next.values, next.draft).name, null);
  assert.deepEqual(mappingIntent(next.draft, next.values).confirmations[0].observation, response(null).observation);
});

test('malformed, partial, mismatched and non-canonical observations fail before applying values', () => {
  for (const mutate of [r => r.observation.targetRecordId = id(2), r => r.observation.targetVersion = -1,
    r => r.observation.revision = 'invalid', r => r.snapshots = {}, r => r.defaults.foreign = {kind: 'STRING', value: 'leak'},
    r => r.defaults.amount = {kind: 'STRING', value: 'wrong type'}]) {
    const value = response(); mutate(value);
    assert.throws(() => mappingEntry(document, {fieldId: 'customer'}, id(1), ['default'], value));
  }
  assert.throws(() => mappingEntry(document, {fieldId: 'customer'}, null, ['default'], response()));
  assert.throws(() => applyMapping({}, [], entry(), ['unknown']));
  assert.throws(() => applyMapping({}, [], entry(), ['default', 'default']));
  assert.throws(() => applyMapping({lines: [{clientId: id(10), values: {}}, {clientId: id(10), values: {}}]}, [],
    entry({fieldId: 'customer', detailId: 'lines', rowKey: id(10)}), []));
});

test('scene draft clones confirmation metadata; cancel cannot modify the parent observation', () => {
  const next = applyMapping({}, [], entry(), ['default']);
  const scene = sceneDraft('nested', next.values, next.draft);
  scene.mappings[0].observation.targetVersion = 8;
  scene.values.amount = '3';
  assert.equal(next.draft[0].observation.targetVersion, 7);
  assert.equal(scene.initialMappings[0].observation.targetVersion, 7);
  assert.equal(next.values.amount, '9007199254740993.12');
  assert.equal(mappingIntent(next.draft, {...next.values, customer: id(2)}), undefined);
});

test('default choices intersect destination grants and new/saved row lifecycle policy', () => {
  const restricted = {...document, details: [{detailId: 'lines', fields: fields.map(field => field.fieldId === 'amount'
    ? {...field, writePolicy: 'CREATE_ONLY'} : field)}]};
  const values = {lines: [{id: id(10), values: {}}, {clientId: id(11), values: {}}]};
  assert.deepEqual(mappingDefaultIds(restricted, values, {fieldId: 'customer', detailId: 'lines', rowKey: id(10)}, 'DRAFT'), []);
  assert.deepEqual(mappingDefaultIds(restricted, values, {fieldId: 'customer', detailId: 'lines', rowKey: id(11)}, 'DRAFT'), ['default']);
  restricted.details[0].readOnlyFieldIds = ['amount'];
  assert.deepEqual(mappingDefaultIds(restricted, values, {fieldId: 'customer', detailId: 'lines', rowKey: id(11)}, 'DRAFT'), []);
});

test('preserve-null defaults are omitted without clearing user input; other missing outputs are rejected', () => {
  const preserve = {...document, fields: fields.map(field => field.reference ? {...field, reference: {...field.reference,
    mappings: field.reference.mappings.map(mapping => mapping.mode === 'EDITABLE_DEFAULT' ? {...mapping, nullPolicy: 'PRESERVE'} : mapping)}} : field)};
  const view = {...response(), defaults: {}};
  const observed = mappingEntry(preserve, {fieldId: 'customer'}, id(1), ['default'], view);
  const next = applyMapping({amount: 'manual'}, [], observed, []);
  assert.equal(next.values.amount, 'manual');
  assert.deepEqual(next.draft[0].defaults, []);
  assert.throws(() => mappingEntry(document, {fieldId: 'customer'}, id(1), ['default'], view), /projection/);
});
