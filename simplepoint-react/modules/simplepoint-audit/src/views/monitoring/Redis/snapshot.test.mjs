import assert from 'node:assert/strict';
import test from 'node:test';
import {SnapshotSession, pollingInterval, canEditRedisDetail, validateScanResponse} from './snapshot.ts';

test('paging and filters pin one scan; stale and cancelled responses cannot replace a refreshed version', () => {
  const session = new SnapshotSession();
  assert.deepEqual(session.parameters(0), {});
  session.accept(0, {status: 'BUILDING', snapshotId: 'first'});
  assert.deepEqual(session.parameters(0), {snapshotId: 'first'});
  const revision = session.refresh();
  assert.deepEqual(session.parameters(revision), {snapshotId: 'first', refresh: true});
  session.accept(0, {status: 'READY', snapshotId: 'obsolete'});
  session.accept(revision, {status: 'BUILDING', snapshotId: 'cancelled'}, true);
  assert.deepEqual(session.parameters(revision), {snapshotId: 'first', refresh: true});
  session.accept(revision, {status: 'BUSY', snapshotId: null});
  assert.deepEqual(session.parameters(revision), {snapshotId: 'first', refresh: true});
  session.accept(revision, {status: 'READY', snapshotId: 'second'});
  assert.deepEqual(session.parameters(revision), {snapshotId: 'second'});
  session.accept(revision, {status: 'EXPIRED', snapshotId: null});
  assert.deepEqual(session.parameters(revision), {snapshotId: 'second'});
  assert.throws(() => session.parameters(0));
});

test('only active pending scans poll; errors and expired versions require explicit recovery', () => {
  for (const status of ['BUILDING', 'BUSY']) {
    assert.equal(pollingInterval(status), 1000);
    assert.equal(pollingInterval(status, false), false);
  }
  for (const status of ['READY', 'EXPIRED', 'FAILED', 'LIMIT_EXCEEDED', 'TIMED_OUT', undefined]) {
    assert.equal(pollingInterval(status), false);
  }
});

test('editing requires an intact readable string, exact key and valid lifetime', () => {
  const detail = {key: ' spaced键 ', type: 'STRING', value: '', editable: true, persistent: true};
  assert.equal(canEditRedisDetail(detail), true);
  for (const field of ['key', 'type', 'value', 'editable', 'persistent']) {
    assert.equal(canEditRedisDetail({...detail, [field]: undefined}), false, field);
    assert.equal(canEditRedisDetail({...detail, [field]: '***'}), false, field);
  }
  assert.equal(canEditRedisDetail({...detail, sampled: true}), false);
  assert.equal(canEditRedisDetail({...detail, truncated: true}), false);
  for (const ttlSeconds of [undefined, 0, -1, 1.5, NaN, Infinity]) {
    assert.equal(canEditRedisDetail({...detail, persistent: false, ttlSeconds}), false);
  }
  assert.equal(canEditRedisDetail({...detail, persistent: false, ttlSeconds: 60}), true);
});

test('invalid scan contracts fail instead of inventing counts or remaining pending forever', () => {
  const page = {content: [{key: 'a'}], page: {number: 0, size: 20, totalElements: 1, totalPages: 1}};
  validateScanResponse({status: 'READY', snapshotId: 'id', page});
  validateScanResponse({status: 'READY', snapshotId: 'id', page: {content: page.content, ...page.page}});
  validateScanResponse({status: 'BUILDING', page: null});
  for (const response of [null, {}, {status: 'UNKNOWN'}, {status: 'READY'},
    {status: 'READY', page}, {status: 'BUILDING', page},
    {status: 'READY', snapshotId: 'id', page: {...page, content: [{key: 'a'}, {key: 'a'}]}},
    {status: 'READY', snapshotId: 'id', page: {...page, page: {...page.page, totalElements: -1}}}]) {
    assert.throws(() => validateScanResponse(response));
  }
});
