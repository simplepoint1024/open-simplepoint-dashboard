import assert from 'node:assert/strict';
import {test} from 'node:test';
import {encodeDocument} from '../Table/query.ts';
import {
  createIdempotencyKey,
  editableValues,
  recordPage,
  recordPageQuery,
  runtimeDescriptorKey,
} from './recordTableModel.ts';

test('a new write floor invalidates the editor even when its immutable page version stays unchanged', () => {
  const page = {formId: 'a', formVersion: 1, document: {fields: [{fieldId: 'name'}]}};
  assert.equal(runtimeDescriptorKey(page), runtimeDescriptorKey({...page, writePolicyVersion: 1}));
  assert.equal(runtimeDescriptorKey(page), runtimeDescriptorKey({...page, writePolicyVersion: 2}), 'layout-only publication has no impact');
  assert.notEqual(runtimeDescriptorKey(page), runtimeDescriptorKey({...page, writePolicyVersion: 2,
    document: {fields: [{fieldId: 'name', writePolicy: 'CREATE_ONLY'}]}}));
  assert.notEqual(runtimeDescriptorKey(page), runtimeDescriptorKey({...page, formVersion: 2}));
});

test('maps the nested APP record envelope to a platform page', () => {
  const page = recordPage({
    page: 1,
    size: 10,
    total: 21,
    records: [{
      id: 'record-a',
      version: 2,
      state: 'DRAFT',
      values: {'name-field': 'Ada'},
    }],
  });

  assert.deepEqual(page.content, [{
    id: 'record-a',
    version: 2,
    recordState: 'DRAFT',
    'name-field': 'Ada',
  }]);
  assert.deepEqual(page.page, {
    size: 10,
    totalElements: 21,
    totalPages: 3,
    number: 1,
  });
});

test('compiles platform filters and sorting to the bounded APP query contract', () => {
  const query = recordPageQuery({
    page: 0,
    size: 20,
    filters: {
      $query: encodeDocument({
        op: 'and',
        children: [
          {field: 'name-field', op: 'like', value: 'Ada'},
          {field: 'amount', op: 'than:equal:greater', value: 10},
        ],
      }),
    },
    sort: ['name-field,asc'],
  });

  assert.deepEqual(query.filter, {
    operator: 'AND',
    values: [],
    children: [
      {operator: 'LIKE', fieldId: 'name-field', values: ['Ada'], children: []},
      {operator: 'GREATER_THAN_OR_EQUAL', fieldId: 'amount', values: [10], children: []},
    ],
  });
  assert.deepEqual(query.sorts, [{fieldId: 'name-field', direction: 'ASC'}]);
});

test('sends only editable changes and reuses a create key for the same payload', () => {
  const values = editableValues(
    {'name-field': 'Grace', 'secret-field': 'hidden'},
    new Set(['name-field']),
    {id: 'record-a', version: 1, recordState: 'DRAFT', 'name-field': 'Ada'},
  );
  assert.deepEqual(values, {'name-field': 'Grace'});

  const first = createIdempotencyKey(undefined, 'SUBMITTED', values);
  const replay = createIdempotencyKey(first, 'SUBMITTED', {'name-field': 'Grace'});
  const changed = createIdempotencyKey(first, 'SUBMITTED', {'name-field': 'Lin'});
  assert.equal(replay.key, first.key);
  assert.notEqual(changed.key, first.key);
});

test('clearing a writable field is an explicit null while unowned fields remain untouched', () => {
  const current = {id: 'a', version: 1, recordState: 'DRAFT', name: 'Ada', secret: 'preserve'};
  for (const formData of [{name: undefined}, {}]) {
    assert.equal(JSON.stringify(editableValues(formData, new Set(['name']), current)), '{"name":null}');
  }
  assert.deepEqual(editableValues({}, new Set(['name'])), {});
});

test('saving then submitting uses the same record and pins the form and record versions', async () => {
  const {recordMutation} = await import('./recordTableModel.ts');
  const descriptor = {formId: 'form-a', formVersion: 3, sceneId: 'main', document: {fields: ['name', 'secret', 'detail'].map(fieldId => ({fieldId})), scenes: [
    {sceneId: 'main', root: {children: [
      {fieldId: 'name', readOnly: false, children: []},
      {fieldId: 'secret', readOnly: true, children: []},
    ]}},
    {sceneId: 'dialog', root: {fieldId: 'detail', readOnly: false, children: []}},
  ]}};
  const initial = recordMutation(descriptor, undefined, {name: 'Ada', secret: 'forbidden'}, 'DRAFT', 'key');
  assert.equal(initial.method, 'POST');
  assert.deepEqual(initial.body, {runtime: {formId: 'form-a', formVersion: 3},
    idempotencyKey: 'key', state: 'DRAFT', values: {name: 'Ada'}});
  const record = {id: 'saved-draft', version: 7, state: 'DRAFT', values: {name: 'Ada', detail: 'preserve'}};
  const submission = recordMutation(descriptor, record, {name: 'Grace', detail: 'preserve'}, 'SUBMITTED', 'unused');
  assert.equal(submission.method, 'PUT');
  assert.equal(submission.recordId, 'saved-draft');
  assert.deepEqual(submission.body, {runtime: {formId: 'form-a', formVersion: 3},
    idempotencyKey: 'unused', expectedVersion: 7, state: 'SUBMITTED', values: {name: 'Grace'}});
  assert.throws(() => recordMutation(descriptor, {...record, state: 'SUBMITTED'}, {}, 'DRAFT', 'key'));
});


test('record mutations and table forms lock business fields using the original record state', async () => {
  const {recordMutation, recordEditorSchema} = await import('./recordTableModel.ts');
  const fields = [
    {fieldId: 'code', writePolicy: 'CREATE_ONLY', submitRequired: true},
    {fieldId: 'amount', writePolicy: 'UNTIL_SUBMITTED'},
    {fieldId: 'note'},
    {fieldId: 'permissionOnly'},
  ];
  const descriptor = {formId: 'a', formVersion: 1, document: {fields, scenes: [{sceneId: 'main', root: {
    children: fields.map(field => ({fieldId: field.fieldId, readOnly: field.fieldId === 'permissionOnly', children: []})),
  }}]}};
  const data = {code: 'changed', amount: '12.00', note: 'comment', permissionOnly: 'forged'};
  const current = {id: 'record', version: 1, state: 'DRAFT', values: {code: 'original', amount: '10.00'}};
  assert.deepEqual(recordMutation(descriptor, current, data, 'SUBMITTED', 'key').body.values,
    {amount: '12.00', note: 'comment'});
  assert.deepEqual(recordMutation(descriptor, {...current, state: 'SUBMITTED'}, data, 'SUBMITTED', 'key').body.values,
    {note: 'comment'});
  const schema = {properties: {id: {}, code: {}, amount: {}, note: {}, permissionOnly: {readOnly: true}}};
  const created = recordEditorSchema(schema, descriptor);
  assert.deepEqual(created.required, ['code']);
  assert.equal(created.properties.code.readOnly, undefined);
  const submitted = recordEditorSchema(schema, descriptor, {recordState: 'SUBMITTED'});
  assert.equal(submitted.properties.code.readOnly, true);
  assert.equal(submitted.properties.amount.readOnly, true);
  assert.equal(submitted.properties.permissionOnly.readOnly, true);
  assert.equal(submitted.properties.permissionOnly['x-display-only'], true);
  assert.equal(submitted.properties.code['x-display-only'], true);
  assert.equal(submitted.properties.amount['x-display-only'], true);
  assert.equal(submitted.properties.note['x-display-only'], undefined);
  assert.equal(submitted.properties.note.readOnly, undefined);
  assert.equal(submitted.properties.id, undefined);
  assert.deepEqual(schema.properties.code, {}, 'must not mutate the shared server schema');
});


test('base metadata is displayed in the list but never joins writable business values', () => {
  const record = recordPage({page: 0, size: 10, total: 1, records: [{id: 'record', version: 1, state: 'DRAFT',
    values: {title: 'business'}, systemValues: {createdBy: 'actor', createOrgDeptId: 'department'},
  }]}).content[0];
  assert.equal(record.createdBy, 'actor');
  assert.equal(record.createOrgDeptId, 'department');
  assert.deepEqual(editableValues(record, new Set(['title'])), {title: 'business'});
});
