import test from 'node:test';
import assert from 'node:assert/strict';
import {newestFirst} from './defaultOrder.ts';

test('creation order ignores later edits and keeps equal timestamps stable', () => {
  const rows=[{id:'a',createdAt:'2026-01-01T00:00:00Z',updatedAt:'2026-12-01T00:00:00Z'},
    {id:'b',createdAt:'2026-02-01T00:00:00Z'},{id:'c',createdAt:'2026-02-01T00:00:00Z'}];
  assert.deepEqual(newestFirst(rows,['name']).map(r=>r.id),['b','c','a']);
  assert.deepEqual(rows.map(r=>r.id),['a','b','c']);
  assert.equal(newestFirst(rows,['sort']),rows);
  assert.equal(newestFirst(rows,['index']),rows);
});
test('absence of creation metadata never invents creation order from random ids',()=>{
  const rows=[{id:'z'},{id:'a'}];assert.equal(newestFirst(rows,['id']),rows);
});
