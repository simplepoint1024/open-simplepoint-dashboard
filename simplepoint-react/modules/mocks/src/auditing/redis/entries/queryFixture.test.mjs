import assert from 'node:assert/strict';
import test from 'node:test';
import {createRedisSnapshotFixture, matchesRedisGlob} from './queryFixture.ts';

test('Redis fixture preserves byte glob, whitespace, escaping and case sensitivity', () => {
  for (const [pattern, key, expected] of [['h?llo', 'hello', true], ['h[ae]llo', 'hello', true],
    ['h[^e]llo', 'hello', false], ['h[a-b]llo', 'hbllo', true], ['a\\*', 'a*', true],
    ['a\\*', 'abc', false], ['Item*', 'item1', false], [' * ', ' key ', true], [' * ', 'key', false],
    ['?', '键', false], ['???', '键', true], ['*', '键', true]]) {
    assert.equal(matchesRedisGlob(pattern, key), expected, `${pattern} / ${key}`);
  }
});

test('Redis fixture pins complete metadata across queries and does not return partial pages', () => {
  const rows = Array.from({length: 520}, (_, size) => ({key: `k${String(size).padStart(3, '0')}`, size}));
  const fixture = createRedisSnapshotFixture(() => rows);
  const initial = fixture.query(new URLSearchParams({page: '25', size: '20'}));
  assert.equal(initial.page.content.length, 20);
  const params = new URLSearchParams({snapshotId: initial.snapshotId, size: '20',
    $query: JSON.stringify({version: 1, where: {field: 'size', op: 'equals', value: 519}})});
  rows[519].size = 0;
  assert.equal(fixture.query(params).page.content[0].key, 'k519');
  assert.equal(fixture.builds, 1);
  fixture.status('BUILDING');
  assert.equal(fixture.query(params).page, null);
  fixture.status(); fixture.invalidate();
  assert.equal(fixture.query(params).status, 'EXPIRED');
  params.set('refresh', 'true');
  assert.equal(fixture.query(params).page.page.totalElements, 0);
  assert.equal(fixture.builds, 2);
});
