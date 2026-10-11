import {test} from 'node:test';
import assert from 'node:assert/strict';
import {hasRecordIdentity} from './recordIdentity.ts';

test('hidden, masked, empty and invalid identifiers cannot authorize row actions', () => {
  for (const row of [{name: 'hidden'}, {id: '***'}, {id: ''}, {id: ' '}, {id: NaN}, null])
    assert.equal(hasRecordIdentity(row), false);
  for (const row of [{id: 'record-id'}, {id: 0}, {key: 'key-based-record'}])
    assert.equal(hasRecordIdentity(row), true);
});
