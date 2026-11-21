import assert from 'node:assert/strict';
import test from 'node:test';
import {inverseCatalog, inverseEndpoint, inverseEditor} from './inverseModel.ts';

const parent = {applicationId: 'customers', pageId: 'directory', formId: 'customer-form', formVersion: 7};
const id = 'aaaaaaaa-1111-2222-3333-000000000001';
const other = 'aaaaaaaa-1111-2222-3333-000000000002';
const runtime = {source: {applicationId: 'trades', pageId: 'trade-page'}, fieldId: 'customer', canUnlink: true};

test('the endpoint binds the observed parent, never a browser-selected source resource', () => {
  const result = inverseEndpoint(parent, id.toUpperCase(), 'trades', runtime);
  assert.equal(result.baseUrl, `/app/runtime/applications/customers/pages/directory/records/${id}/relations/trades/records`);
  assert.deepEqual(result.params, {parentFormId: 'customer-form', parentFormVersion: '7'});
  assert.deepEqual(result.initialValues, {customer: id});
  assert.equal(result.fixedReferenceField, 'customer');
  assert(!result.baseUrl.includes('trade-page'));
});

test('changing parent or publication changes requests while keeping shared view appearance preferences', () => {
  const first = inverseEndpoint(parent, id, 'trades', runtime);
  const second = inverseEndpoint({...parent, formVersion: 8}, other, 'trades', runtime);
  assert.equal(first.preferenceKey, second.preferenceKey);
  assert.notEqual(first.baseUrl, second.baseUrl);
  assert.notDeepEqual(first.params, second.params);
});

test('owned references seed one normal uncommitted detail row without inventing saved row identity', () => {
  const result = inverseEndpoint(parent, id, 'trades', {...runtime, detailId: 'lines'});
  assert.deepEqual(result.initialValues, {lines: [{values: {customer: id}}]});
  assert.equal(result.fixedReferenceField, undefined);
  assert.deepEqual(runtime, {source: {applicationId: 'trades', pageId: 'trade-page'}, fieldId: 'customer', canUnlink: true});
});

test('unsaved identities and unsupported versions never become lookup routes', () => {
  for (const invalid of ['', 'draft', '1-1-1-1-1', '../other']) assert.throws(() => inverseCatalog(parent, invalid));
  for (const version of [0, -1, 1.5, 2147483648, NaN]) assert.throws(() => inverseCatalog({...parent, formVersion: version}, id));
  assert.throws(() => inverseEndpoint(parent, id, '../other', runtime));
});

test('the root editor fixes its parent while owned rows retain their independent field controls', () => {
  const source = {document: {fields: [{fieldId: 'customer', access: {create: 'WRITE'}}, {fieldId: 'note'}]}};
  const view = {...runtime, source};
  const projected = inverseEditor(view);
  assert.equal(projected.document.fields[0].access.readOnly, true);
  assert.equal(projected.document.fields[0].access.create, 'WRITE');
  assert.equal(source.document.fields[0].access.readOnly, undefined);
  assert.strictEqual(projected.document.fields[1], source.document.fields[1]);
  assert.strictEqual(inverseEditor({...view, detailId: 'lines'}), source);
});
