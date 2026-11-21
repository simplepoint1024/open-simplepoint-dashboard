import test from 'node:test';
import assert from 'node:assert/strict';
import {referenceSource} from './source.ts';

test('reference search preserves server totals and shares the selector cancellation signal', async () => {
  const controller = new AbortController();
  const source = referenceSource('relation/form-version', {
    search: async (keyword, page, size, signal) => {
      assert.deepEqual([keyword, page, size], ['客户', 1, 50]);
      assert.equal(signal, controller.signal);
      return {page: 1, size: 50, total: 61, records: [{id: 'customer', version: 4, label: '客户', values: {name: '客户'}}]};
    }, resolve: async () => [],
  });
  const result = await source.search('客户', 1, 50, controller.signal, {});
  assert.deepEqual(result.page, {number: 1, size: 50, totalElements: 61, totalPages: 2});
  assert.deepEqual(source.option(result.content[0]), {value: 'customer', label: '客户', metadata: {version: 4, values: {name: '客户'}, canDrillThrough: false}});
  assert.equal(source.option({...result.content[0], canDrillThrough: true}).metadata.canDrillThrough, true);
  assert.equal(source.option({...result.content[0], canDrillThrough: 'true'}).metadata.canDrillThrough, false);
});

test('hydration batches IDs and never accepts unrequested rows or invents missing labels', async () => {
  const requests = [];
  const source = referenceSource('relation', {
    search: async () => {throw new Error('Hydration must not search');},
    resolve: async (ids, signal) => {
      assert.equal(signal.aborted, false);
      requests.push(ids);
      return [{id: ids[0], label: '可读', version: 1, values: {}}, {id: 'injected', label: 'foreign', version: 1, values: {}}];
    },
  });
  const ids = Array.from({length: 201}, (_, i) => String(i));
  const options = await source.resolve([...ids, '0'], new AbortController().signal);
  assert.deepEqual(requests.map(batch => batch.length), [100, 100, 1]);
  assert.deepEqual(options.map(option => option.value), ['0', '100', '200']);
});

test('aborting hydration fences the result and stops subsequent batches', async () => {
  const controller = new AbortController();
  let calls = 0;
  const source = referenceSource('relation', {
    search: async () => {throw new Error('unused');},
    resolve: async () => {calls++; controller.abort(); return [];},
  });
  await assert.rejects(source.resolve(Array.from({length: 101}, (_, i) => String(i)), controller.signal), {name: 'AbortError'});
  assert.equal(calls, 1);
});
