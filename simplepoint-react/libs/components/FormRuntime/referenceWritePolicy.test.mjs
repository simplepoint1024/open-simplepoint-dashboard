import assert from 'node:assert/strict';
import test from 'node:test';
import {fieldWriteAllowed} from './writePolicy.ts';
import {detailPayload} from '../FormComponents/Detail/payload.ts';
import {detailRowSchema} from '../FormComponents/Detail/schema.ts';

const field = (fieldId, referenceValueMode) => ({fieldId, label: fieldId, type: 'STRING', choices: [],
  submitRequired: true, referenceValueMode});
const fields = [field('copy', 'SERVER_SNAPSHOT'), field('current', 'LIVE'), field('suggestion', 'EDITABLE_DEFAULT')];

test('server and live outputs stay read-only while suggestions obey ordinary lifecycle policy', () => {
  for (const state of [undefined, 'DRAFT', 'SUBMITTED']) {
    assert.equal(fieldWriteAllowed(fields[0], state), false);
    assert.equal(fieldWriteAllowed(fields[1], state), false);
    assert.equal(fieldWriteAllowed(fields[2], state), true);
  }
  assert.equal(fieldWriteAllowed({...fields[2], writePolicy: 'UNTIL_SUBMITTED'}, 'SUBMITTED'), false);
  assert.equal(fieldWriteAllowed({...fields[2], access: {edit: 'READ'}}, 'DRAFT'), false);
  assert.equal(fieldWriteAllowed(field('legacy')), true);
});

test('owned row writes strip display outputs but preserve editable defaults and exact row identity', () => {
  const document = {fields: [], details: [{detailId: 'lines', fields}]};
  const row = {id: 'row', version: 7, values: {copy: 'server', current: 'live', suggestion: '9007199254740993.12'}};
  const result = detailPayload(document, {lines: [row]}, 'DRAFT');
  assert.deepEqual(result.lines, [{id: 'row', version: 7, values: {suggestion: '9007199254740993.12'}}]);
  assert.equal(row.values.copy, 'server');
  const schema = detailRowSchema(document.details[0], 'DRAFT');
  assert.equal(schema.properties.copy.readOnly, true);
  assert.equal(schema.properties.current.readOnly, true);
  assert.deepEqual(schema.required, ['suggestion']);
});
