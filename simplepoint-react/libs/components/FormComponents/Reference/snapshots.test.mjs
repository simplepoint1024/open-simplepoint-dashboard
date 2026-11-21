import test from 'node:test';
import assert from 'node:assert/strict';
import {snapshotLabels, snapshotLabelKey} from './snapshots.ts';
const id = 'aaaaaaaa-1111-2222-3333-444444444444';
const row = {relationId: 'customer', targetRecordId: id, targetVersion: 3, label: '旧名称-日本語😀'};

test('frozen names bind to relation and canonical identity, independently of another relation', () => {
  const labels = snapshotLabels([row, {...row, relationId: 'supplier', label: '供应商'}]);
  assert.deepEqual(labels.get(snapshotLabelKey('customer', id)), {id, label: row.label});
  assert.equal(labels.get(snapshotLabelKey('supplier', id)).label, '供应商');
  assert.equal(snapshotLabels([]).size, 0);
});

test('repeated detail targets preserve one label but conflicting immutable evidence stays unavailable', () => {
  const key = snapshotLabelKey('customer', id);
  assert.equal(snapshotLabels([row, {...row, targetRecordId: id.toUpperCase()}]).get(key).label, row.label);
  assert.deepEqual(snapshotLabels([row, {...row, label: 'new'}, row]).get(key), {id, label: null});
  assert.deepEqual(snapshotLabels([row, {...row, targetVersion: 4}]).get(key), {id, label: null});
});

test('masked identities cannot resolve and Unicode labels retain bounded display', () => {
  assert.equal(snapshotLabels([{...row, targetRecordId: '***'}]).size, 0);
  assert.equal(snapshotLabels([{...row, label: null}]).get(snapshotLabelKey('customer', id)).label, null);
  assert.equal(snapshotLabels([{...row, label: '😀'.repeat(300)}]).get(snapshotLabelKey('customer', id)).label,
    '😀'.repeat(255) + '…');
});
