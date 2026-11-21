import assert from 'node:assert/strict';
import test from 'node:test';
import {applyInverseView} from './inverseEdit.ts';

const source = {applicationId: 'sales', formId: 'trades', accessResourceId: 'trade-entry'};
const metadata = {source, formVersion: 3, relations: [{relationId: 'customer', nullable: true}]};
const view = {viewId: 'trades', label: '交易记录', source, sourceRelationId: 'customer',
  allowCreate: false, allowEdit: false, allowDelete: false, allowUnlink: true};
const document = {contractVersion: '1', fields: [], indexes: [], scenes: [], rules: [], presentation: {listFieldOrder: ['code'], labelWidth: 160}};

test('one inverse edit preserves presentation settings and stable identity without changing the input', () => {
  const added = applyInverseView(document, undefined, view, metadata);
  assert.deepEqual(added.presentation.inverseViews, [view]);
  assert.equal(document.presentation.inverseViews, undefined);
  const changed = applyInverseView(added, view, {...view, label: '取引', allowEdit: true}, metadata);
  assert.equal(changed.presentation.inverseViews[0].viewId, view.viewId);
  assert.equal(changed.presentation.inverseViews[0].labelLocalization, undefined);
  assert.equal(changed.presentation.labelWidth, 160);
  assert.deepEqual(changed.presentation.listFieldOrder, ['code']);
  assert.deepEqual(applyInverseView(added, view, undefined), document);
});

test('stale edits, duplicate IDs and changed source metadata cannot overwrite the document', () => {
  const added = applyInverseView(document, undefined, view, metadata);
  assert.throws(() => applyInverseView(added, undefined, view, metadata), /conflict/);
  assert.throws(() => applyInverseView(added, {...view, label: 'old'}, view, metadata), /conflict/);
  assert.throws(() => applyInverseView(added, view, {...view, viewId: 'other'}, metadata), /conflict/);
  assert.throws(() => applyInverseView(document, undefined, view, {...metadata, source: {...source, formId: 'other'}}), /target/);
  assert.throws(() => applyInverseView(document, undefined, view, {...metadata, relations: []}), /target/);
});

test('nonnullable references cannot expose unlink and view limits stay bounded', () => {
  assert.throws(() => applyInverseView(document, undefined, view, {...metadata, relations: [{relationId: 'customer', nullable: false}]}), /target/);
  const full = {...document, presentation: {...document.presentation, inverseViews: Array.from({length: 20}, (_, i) => ({...view, viewId: `view-${i}`}))}};
  assert.throws(() => applyInverseView(full, undefined, view, metadata), /limit/);
});
