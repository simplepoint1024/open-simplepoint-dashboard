import test from 'node:test';
import assert from 'node:assert/strict';
import {entityLookupBatches, entityLookupParams, entitySearchParams, mergeEntityOptions} from './query.ts';

test('multiple selected identities are deduplicated and read in bounded batches without truncation', () => {
  const ids = Array.from({length: 241}, (_, index) => `item:${index},literal`);
  const batches = entityLookupBatches([...ids, ids[0], '']);
  assert.deepEqual(batches.map(batch => batch.size), [100, 100, 41]);
  assert.deepEqual(batches.flatMap(batch => JSON.parse(batch.id).where.value), ids);
  assert.deepEqual(entityLookupBatches(['one']), [entityLookupParams('one')]);
  assert.deepEqual(entityLookupBatches([]), []);
});

test('entity search uses bounded pages and literal typed input, not a truncated local catalog', () => {
  const params = entitySearchParams('  名称:50%_"  ', 8);
  assert.equal(params.page, 8);
  assert.equal(params.size, 20);
  assert.equal(params.sort, 'name,asc');
  assert.deepEqual(JSON.parse(params.name).where, {field: 'name', op: 'like', value: '名称:50%_"'});
  assert.equal('name' in entitySearchParams('', 0), false);
});
test('selected identity lookup is exact and independent of search; options preserve distinct IDs', () => {
  const params = entityLookupParams('id:with,comma');
  assert.equal(params.size, 1);
  assert.deepEqual(JSON.parse(params.id).where, {field: 'id', op: 'equals', value: 'id:with,comma'});
  assert.deepEqual(mergeEntityOptions([{id: 'a', name: 'one'}, {id: 'b', name: null}, {id: 'a', name: 'updated'}, {id: ''}]),
    [{value: 'a', label: 'updated'}, {value: 'b', label: 'b'}]);
});
