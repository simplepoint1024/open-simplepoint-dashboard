import assert from 'node:assert/strict';
import test from 'node:test';
import {publishIntent, releaseIsActive} from './publication.ts';

const intent = () => ({formId: 'form-a', revision: 4, checksum: 'saved-design', pageId: 'page-a', idempotencyKey: 'same-operation'});
const release = version => ({dataPublication: {id: 'publication-a', formId: 'form-a', formVersion: version, status: 'ACTIVE'},
  pageBinding: {pageId: 'page-a', formId: 'form-a', formVersion: version, status: 'ACTIVE'}});

test('lost freeze response recovers the captured design even after another editor saves a newer one', async () => {
  const captured = intent(); let published;
  const result = await publishIntent(captured, {current: () => true, mayReconcile: () => true,
    freeze: async () => {throw new Error('lost response');},
    versions: async () => [{formId: 'form-a', version: 6, checksum: 'other-design'}, {formId: 'form-a', version: 5, checksum: 'saved-design'}],
    publish: async (version, command) => {published = {version, command}; return release(version);},
  });
  assert.deepEqual(published, {version: 5, command: {pageId: 'page-a', idempotencyKey: 'same-operation'}});
  assert.equal(releaseIsActive(result), true);
});

test('lost publication response retries the same immutable version, destination and key without freezing again', async () => {
  const captured = intent(); let freezes = 0; const calls = [];
  const transport = {current: () => true, mayReconcile: () => true,
    freeze: async () => {freezes++; return {formId: 'form-a', version: 5, checksum: 'saved-design'};},
    versions: async () => [], publish: async (version, command) => {calls.push({version, command});
      if (calls.length === 1) throw new Error('lost publication response'); return release(version);},
  };
  await assert.rejects(publishIntent(captured, transport));
  await publishIntent(captured, transport);
  assert.equal(freezes, 1); assert.deepEqual(calls[0], calls[1]);
});

test('a changed authorization session cannot publish a completed freeze and incomplete release is not success', async () => {
  let current = true, writes = 0;
  await assert.rejects(publishIntent(intent(), {current: () => current, mayReconcile: () => true,
    freeze: async () => {current = false; return {formId: 'form-a', version: 5, checksum: 'saved-design'};},
    versions: async () => [], publish: async () => {writes++; return release(5);},
  }), {name: 'AbortError'});
  assert.equal(writes, 0);
  assert.equal(releaseIsActive({dataPublication: release(5).dataPublication}), false);
  assert.equal(releaseIsActive({...release(5), pageBinding: {...release(5).pageBinding, status: 'FAILED'}}), false);
});

test('a known validation failure never recovers a different version or starts a publication', async () => {
  let reads = 0, writes = 0;
  await assert.rejects(publishIntent(intent(), {current: () => true, mayReconcile: () => false,
    freeze: async () => {throw new Error('required input missing');},
    versions: async () => {reads++; return [];}, publish: async () => {writes++; return release(5);},
  }), /required input missing/);
  assert.equal(reads, 0); assert.equal(writes, 0);
});

test('an intent retired before data preparation is a valid terminal response, never an active release', async () => {
  const captured = {...intent(), version: 5};
  const retired = {dataPublication: null, pageBinding: {...release(5).pageBinding, status: 'RETIRED'}};
  const result = await publishIntent(captured, {current: () => true, mayReconcile: () => true,
    freeze: async () => {assert.fail('a replay must not freeze');}, versions: async () => [],
    publish: async () => retired,
  });
  assert.equal(result, retired);
  assert.equal(releaseIsActive(result), false);
});
