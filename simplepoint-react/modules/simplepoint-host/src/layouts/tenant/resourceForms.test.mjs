import test from 'node:test';
import assert from 'node:assert/strict';
import validator from '@rjsf/validator-ajv8';
import {resourceDraft, resourceFormSchema, resourcePayload} from './resourceForms.ts';
const t = (_key, fallback) => fallback;
const valid = (schema, data) => !validator.rawValidation(schema, data).errors?.length;
test('local pages need a real target and an explicitly supplied supported route', () => {
  const schema = resourceFormSchema(t, null, 'PAGE');
  const draft = {name: 'Actual page', type: 'PAGE', path: '/workspace/actual', targetResourceId: 'actual-platform-id'};
  assert.equal(valid(schema, draft), true);
  for (const invalid of [{...draft, targetResourceId: null}, {...draft, targetResourceId: ''}, {...draft, path: null},
    {...draft, path: '//example.test'}, {...draft, path: ''}, {...draft, path: '/bad path'}]) assert.equal(valid(schema, invalid), false);
  assert.equal(resourceDraft(null).path, undefined, 'no fabricated example route');
});
test('resource values are typed, bounded and not populated with protected fields', () => {
  const schema = resourceFormSchema(t, null, 'GROUP');
  assert.equal(valid(schema, {name: 'Actual', type: 'GROUP', sort: 2, enabled: true}), true);
  assert.equal(valid(schema, {name: 'Actual', type: 'GROUP', sort: 1.5}), false);
  assert.equal(valid(schema, {name: ' ', type: 'GROUP'}), false);
  assert.equal(valid(schema, {name: 'Actual', type: 'API'}), false);
  const payload = resourcePayload({name: ' Actual ', type: 'GROUP', sort: 0, enabled: false, parentId: '', id: 'foreign', code: 'protected'});
  assert.equal(payload.name, 'Actual'); assert.equal(payload.enabled, false); assert.equal(payload.parentId, null);
  assert.equal('id' in payload, false); assert.equal('code' in payload, false);
});
test('platform types stay readonly and existing absent sort values are not rewritten to zero', () => {
  const row = {id: 'actual', name: 'Actual', type: 'FEATURE', code: 'feature', platformResourceId: 'actual', enabled: true, sort: null};
  const schema = resourceFormSchema(t, row, row.type);
  assert.equal(schema.properties.type.readOnly, true); assert.equal(valid(schema, resourceDraft(row)), true);
  assert.equal(resourcePayload(resourceDraft(row)).sort, null);
});
