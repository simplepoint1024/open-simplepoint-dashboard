import assert from 'node:assert/strict';
import {test} from 'node:test';
import {isEntitySelectSource} from './source.ts';

test('form directory adapters accept actual client functions, not JSON pretending to be an executable source', () => {
  const source = {key: 'actual-domain-v1', searchParams: () => ({}), option: () => undefined};
  assert.equal(isEntitySelectSource(source), true);
  assert.equal(isEntitySelectSource({...source, resolve: async () => []}), true);
  assert.equal(isEntitySelectSource({...source, search: async () => ({content: []})}), true);
  for (const value of [null, [], {}, {...source, key: ' '}, {...source, option: '() => undefined'}, {...source, resolve: true}, {...source, search: true}])
    assert.equal(isEntitySelectSource(value), false);
});
