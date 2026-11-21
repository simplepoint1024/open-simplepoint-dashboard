import assert from 'node:assert/strict';
import test from 'node:test';
import {canUnlinkRecord, unlinkCandidates, unlinkValues} from './inverseUnlink.ts';

const id = 'aaaaaaaa-1111-2222-3333-000000000001', other = 'aaaaaaaa-1111-2222-3333-000000000002';
const relation = {targetRecordId: id, field: {fieldId: 'customer', submitRequired: false}};
const record = {id, version: 3, state: 'DRAFT', values: {customer: id, note: 'unchanged'}};

test('root unlink sends only the nullable reference and rejects changed parents', () => {
  assert.deepEqual(unlinkValues(record, relation), {customer: null});
  assert.throws(() => unlinkValues({...record, values: {customer: other}}, relation));
  assert.throws(() => unlinkValues(record, relation, id));
  assert.equal(record.values.customer, id);
});

test('owned unlink preserves all ordered identities and versions, including unrelated rows', () => {
  const rows = [{id, version: 7, values: {customer: id, note: 'first'}},
    {id: other, version: 4, values: {customer: other}}];
  const owned = {...relation, detailId: 'lines'}, saved = {...record, values: {lines: rows}};
  assert.deepEqual(unlinkCandidates(saved, owned).map(row => [row.id, row.ordinal]), [[id, 1]]);
  assert.deepEqual(unlinkValues(saved, owned, id), {lines: [
    {id, version: 7, values: {customer: null}}, {id: other, version: 4, values: {}},
  ]});
  assert.throws(() => unlinkValues(saved, owned, other));
  assert.throws(() => unlinkValues(saved, owned));
  assert.equal(rows[0].values.customer, id);
});

test('missing, duplicate, unsaved or unversioned detail rows never become a replacement collection', () => {
  const owned = {...relation, detailId: 'lines'}, valid = {id, version: 1, values: {customer: id}};
  for (const rows of [undefined, {}, [valid, valid], [{...valid, version: undefined}], [{...valid, id: 'draft'}]]) {
    assert.throws(() => unlinkValues({...record, values: {lines: rows}}, owned, id));
  }
});

test('unlink follows source write policy and submitted required fields, independently of fixed editor projection', () => {
  assert.equal(canUnlinkRecord(relation, 'DRAFT'), true);
  for (const field of [{access: {readOnly: true}}, {access: {edit: 'READ'}}, {writePolicy: 'CREATE_ONLY'}, {calculated: true}]) {
    const blocked = {...relation, field: {...relation.field, ...field}};
    assert.equal(canUnlinkRecord(blocked, 'DRAFT'), false);
    assert.throws(() => unlinkValues(record, blocked));
  }
  assert.equal(canUnlinkRecord({...relation, field: {...relation.field, writePolicy: 'UNTIL_SUBMITTED'}}, 'SUBMITTED'), false);
  assert.equal(canUnlinkRecord({...relation, field: {...relation.field, submitRequired: true}}, 'SUBMITTED'), false);
  assert.equal(canUnlinkRecord(undefined, 'DRAFT'), false);
});
