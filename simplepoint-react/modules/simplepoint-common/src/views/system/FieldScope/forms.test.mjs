import test from 'node:test';
import assert from 'node:assert/strict';
import validator from '@rjsf/validator-ajv8';
import {readFileSync} from 'node:fs';
import {canCreateFieldScope, canEditFieldScopeMetadata, duplicateFieldEntries, fieldScopeDraft, fieldScopePayload,
  fieldScopeMetadataPayload, fieldScopeMetadataSchema, fieldScopeSchema, usableConfiguration} from './forms.ts';
const schema = fieldScopeSchema((_key, fallback) => fallback);
const valid = data => !validator.rawValidation(schema, data).errors?.length;
test('matrix uses typed standard validation without fake resource or field values', () => {
  const draft = fieldScopeDraft(); assert.deepEqual(draft.entries, []); assert.equal(valid(draft), false);
  assert.equal(valid({...draft, name: 'Actual', description: null}), true);
  assert.equal(valid({...draft, name: ' '}), false); assert.equal(valid({...draft, name: 'a'.repeat(101)}), false);
  assert.equal(valid({...draft, name: 'Actual', description: 'a'.repeat(201)}), false);
  for (const entry of [{resource: 'Actual', field: 'name', access: 'OWNER'}, {resource: '', field: 'name', access: 'VISIBLE'},
    {resource: 'Actual', field: ' ', access: 'VISIBLE'}]) assert.equal(valid({...draft, name: 'Actual', entries: [entry]}), false);
  assert.equal(valid({...draft, name: 'Actual', entries: Array.from({length: 501}, (_, i) => ({resource: 'Actual', field: 'field' + i, access: 'VISIBLE'}))}), false);
});
test('captured rows and payloads preserve off-page rules and discard protected entity fields', () => {
  const row = {id: 'current', name: ' Current ', description: null, tenantId: 'protected',
    entries: [{resource: 'Actual', field: 'name', access: 'MASKED', id: 'protected', fieldScopeId: 'foreign'}]};
  const draft = fieldScopeDraft(row); draft.entries[0].access = 'VISIBLE'; assert.equal(row.entries[0].access, 'MASKED');
  assert.deepEqual(fieldScopePayload(draft, 'current'), {id: 'current', name: 'Current', description: null,
    entries: [{resource: 'Actual', field: 'name', access: 'VISIBLE'}]});
});
test('a resource field cannot occur twice even with different access levels', () => {
  const first = {resource: 'Actual', field: 'name', access: 'VISIBLE'};
  assert.equal(duplicateFieldEntries({name: 'Actual', entries: [first, {...first, access: 'MASKED'}]}), true);
  assert.equal(duplicateFieldEntries({name: 'Actual', entries: [first, {...first, resource: 'Other'}]}), false);
});
test('hidden, masked and malformed configurations cannot become an empty editable draft', () => {
  const row = {id: 'Actual', name: 'Actual', entries: [{resource: 'Actual', field: 'name', access: 'VISIBLE'}]};
  assert.equal(usableConfiguration(row), true);
  for (const invalid of [{...row, id: '***'}, {...row, name: '***'}, {...row, entries: undefined}, {...row, entries: '***'},
    {...row, entries: [{...row.entries[0], resource: '***'}]}, {...row, entries: [{...row.entries[0], field: '***'}]},
    {...row, entries: [{...row.entries[0], access: '***'}]}]) {
    assert.equal(usableConfiguration(invalid), false);
    assert.throws(() => fieldScopeDraft(invalid), /unavailable/);
  }
});
test('real root and nested field policies are retained without confusing boolean schemas', () => {
  const source = {properties: {name: {type: 'string', readOnly: true}, description: true,
    entries: {type: 'array', readOnly: true, items: {properties: {resource: {type: 'string'}, field: false,
      access: {type: 'string', readOnly: true}}}}}};
  const schema = fieldScopeSchema((_key, fallback) => fallback, source);
  assert.equal(schema.properties.name.readOnly, true);
  assert.equal(schema.properties.description.readOnly, false);
  assert.equal(schema.properties.entries.readOnly, true);
  assert.equal(schema.properties.entries.items.properties.resource.readOnly, false);
  assert.equal(schema.properties.entries.items.properties.field.readOnly, true);
  assert.equal(schema.properties.entries.items.properties.access.readOnly, true);
  assert.equal('name' in fieldScopeSchema((_key, fallback) => fallback, {properties: {}}).properties, false);
});

test('protected names cannot block a readable configuration or be sent as fabricated required values', () => {
  const source = {properties: {name: {type: 'string', readOnly: true, 'x-field-access': 'MASKED'},
    description: {type: ['string', 'null']}, entries: {type: 'array', items: {properties: {
      resource: {type: 'string'}, field: {type: 'string'}, access: {type: 'string'},
    }}}}};
  const row = {id: 'own-policy', name: '***', description: 'Allowed', entries: [{resource: 'Actual', field: 'name', access: 'VISIBLE'}]};
  assert.equal(usableConfiguration(row, source), true);
  const draft = fieldScopeDraft(row, source), schema = fieldScopeSchema((_key, fallback) => fallback, source);
  assert.equal(validator.rawValidation(schema, draft).errors?.length ?? 0, 0);
  assert.deepEqual(fieldScopePayload(draft, row.id, source), {id: row.id, description: 'Allowed', entries: row.entries});
});

test('hidden metadata properties remain absent in the genuine matrix schema and payload', () => {
  const source = {properties: {description: {type: ['string', 'null']}, entries: {type: 'array', items: {properties: {
    resource: {type: 'string'}, field: {type: 'string'}, access: {type: 'string'},
  }}}}};
  const row = {id: 'own-policy', description: 'Allowed', entries: []};
  assert.equal(usableConfiguration(row, source), true);
  const draft = fieldScopeDraft(row, source), schema = fieldScopeSchema((_key, fallback) => fallback, source);
  assert.equal('name' in schema.properties, false); assert.equal('name' in draft, false);
  assert.equal(validator.rawValidation(schema, draft).errors?.length ?? 0, 0);
  assert.deepEqual(fieldScopePayload(draft, row.id, source), {id: row.id, description: 'Allowed', entries: []});
});

for (const mode of ['hidden-name', 'masked-name', 'hidden-entries', 'masked-entries', 'masked-child', 'hidden-description', 'masked-description']) {
  test(`actual HTTP contract ${mode} supports lossless protected editing without fabricated fields`, () => {
    const base = new URL('../../../../../../scripts/field-scope-contracts/', import.meta.url);
    const source = JSON.parse(readFileSync(new URL(mode + '-schema.json', base))).schema;
    const row = JSON.parse(readFileSync(new URL(mode + '-row.json', base)));
    assert.equal(canEditFieldScopeMetadata(row, source), true);
    assert.equal(canCreateFieldScope(source), !mode.endsWith('name'));
    const matrix = !['hidden-entries', 'masked-entries', 'masked-child'].includes(mode);
    assert.equal(usableConfiguration(row, source), matrix);
    const metadata = fieldScopeMetadataSchema(source, (_key, fallback) => fallback);
    assert.equal('entries' in metadata.properties, false);
    assert.equal(validator.rawValidation(metadata, row).errors?.length ?? 0, 0);
    const payload = fieldScopeMetadataPayload({...row, tenantId: 'foreign'}, row.id, source);
    assert.equal('entries' in payload, false); assert.equal('tenantId' in payload, false);
    for (const key of ['name', 'description']) {
      assert.equal(key in metadata.properties, key in source.properties);
      assert.equal(key in payload, !!source.properties[key] && !source.properties[key].readOnly);
    }
    if (matrix) {
      const draft = fieldScopeDraft(row, source), schema = fieldScopeSchema((_key, fallback) => fallback, source);
      assert.equal(validator.rawValidation(schema, draft).errors?.length ?? 0, 0);
      assert.deepEqual(fieldScopePayload(draft, row.id, source), {...payload, entries: row.entries});
    } else assert.throws(() => fieldScopeDraft(row, source), /unavailable/);
  });
}
