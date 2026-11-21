import assert from 'node:assert/strict';
import test from 'node:test';
import {formReferenceLocation, readFormReferenceLocation} from './referenceLocation.ts';
import {pageViewLocation} from '../../shared/page/viewIdentity.ts';

const source = {applicationId: 'sales', pageId: 'trades', formId: 'trade', formVersion: 3};
const recordId = 'aaaaaaaa-1111-2222-3333-000000000001';

test('a bookmark fixes source publication and relation while leaving target resolution to the server', () => {
  const location = formReferenceLocation(source, 'customer', recordId.toUpperCase());
  assert.deepEqual(readFormReferenceLocation(location), {state: 'reference', reference: {...source, relationId: 'customer', recordId}});
  assert.deepEqual(readFormReferenceLocation(`/app/runtime/sales/trades?filter=active`), {state: 'none'});
});

test('invalid authoring identities, versions and masked references cannot create links', () => {
  for (const version of [0, -1, 1.5, 2147483648, NaN]) assert.throws(() => formReferenceLocation({...source, formVersion: version}, 'customer', recordId));
  for (const id of ['***', '../record', '', 'not-a-uuid']) assert.throws(() => formReferenceLocation(source, 'customer', id));
  assert.throws(() => formReferenceLocation({...source, applicationId: '../other'}, 'customer', recordId));
});

test('restored links reject incompatible tuples and noncanonical routes instead of opening a list', () => {
  const valid = {version: 1, kind: 'form-reference', identity: ['trade', '3', 'customer', recordId]};
  for (const view of [{...valid, kind: 'unknown'}, {...valid, identity: valid.identity.slice(1)},
    ...['0', '03', '2147483648'].map(version => ({...valid, identity: ['trade', version, 'customer', recordId]})),
    {...valid, identity: ['trade', '3', 'customer', recordId.toUpperCase()]}]) {
    assert.deepEqual(readFormReferenceLocation(pageViewLocation('/app/runtime/sales/trades', view)), {state: 'invalid'});
  }
  for (const route of ['/other/sales/trades', '/app/runtime/sales/trades/extra', '/app//runtime/sales/trades', '/app/runtime/sales/trades/']) {
    assert.deepEqual(readFormReferenceLocation(pageViewLocation(route, valid)), {state: 'invalid'});
  }
});
