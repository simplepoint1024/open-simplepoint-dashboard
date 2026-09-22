import assert from 'node:assert/strict';
import test from 'node:test';
import {setResourceGrant, resourceChanges, displayedGrant} from './resourceDraft.ts';
const row = (id, granted = false, revision = 'v1') => ({id, code: `code-${id}`, granted, revision, grantable: true});

test('page/search refresh never overwrites off-page drafts or their original revisions', () => {
  const first = row('first'), last = row('last', true);
  let draft = setResourceGrant({}, first, true);
  draft = setResourceGrant(draft, last, false);
  assert.equal(displayedGrant(draft, row('first', false, 'v2')), true);
  assert.deepEqual(resourceChanges(draft), [
    {resourceId: 'first', resourceCode: 'code-first', revision: 'v1', granted: true},
    {resourceId: 'last', resourceCode: 'code-last', revision: 'v1', granted: false},
  ]);
  draft = setResourceGrant(draft, row('first', true, 'v2'), false);
  assert.equal(resourceChanges(draft).length, 1);
  assert.equal(resourceChanges(draft)[0].resourceId, 'last');
});

test('undo removes only the selected edit; unvisited resources never become revocations', () => {
  let draft = setResourceGrant({}, row('a'), true);
  draft = setResourceGrant(draft, row('b', true), false);
  draft = setResourceGrant(draft, row('a'), false);
  assert.deepEqual(resourceChanges(draft).map(change => change.resourceCode), ['code-b']);
  assert.equal(displayedGrant(draft, row('unvisited', true)), true);
});

test('structural resources cannot be granted, and serialization never includes future policies', () => {
  assert.deepEqual(setResourceGrant({}, {...row('group'), grantable: false}, true), {});
  const draft = setResourceGrant({}, {...row('a'), dataPermission: {supported: false}, fieldPermission: {supported: false}}, true);
  assert.deepEqual(Object.keys(resourceChanges(draft)[0]).sort(), ['granted', 'resourceCode', 'resourceId', 'revision']);
});
