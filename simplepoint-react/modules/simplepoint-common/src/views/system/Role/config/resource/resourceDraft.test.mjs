import assert from 'node:assert/strict';
import test from 'node:test';
import {setResourceGrant, setResourceGrants, setResourcePolicy, resourceChanges, displayedGrant, displayedPolicy} from './resourceDraft.ts';
const row = (id, granted = false, revision = 'v1') => ({id, code: `code-${id}`, granted, revision,
  grantable: true, dataPermission: {supported: true, policyId: null},
  fieldPermission: {supported: true, policyId: null}});

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

test('structural resources cannot be granted, and unchanged policies are omitted', () => {
  assert.deepEqual(setResourceGrant({}, {...row('group'), grantable: false}, true), {});
  const draft = setResourceGrant({}, {...row('a'), dataPermission: {supported: false}, fieldPermission: {supported: false}}, true);
  assert.deepEqual(Object.keys(resourceChanges(draft)[0]).sort(), ['granted', 'resourceCode', 'resourceId', 'revision']);
});

test('policy updates retain the original revision and can be cleared or undone', () => {
  const baseline = {...row('page', true), dataPermission: {supported: true, policyId: 'old'}};
  let draft = setResourcePolicy({}, baseline, 'data', 'new');
  assert.equal(displayedPolicy(draft, row('page', true, 'v2'), 'data'), 'new');
  assert.deepEqual(resourceChanges(draft), [{resourceId: 'page', resourceCode: 'code-page',
    revision: 'v1', granted: true, dataPermission: {policyId: 'new'}}]);
  draft = setResourcePolicy(draft, baseline, 'data', null);
  assert.deepEqual(resourceChanges(draft)[0].dataPermission, {policyId: null});
  draft = setResourcePolicy(draft, baseline, 'data', 'old');
  assert.deepEqual(resourceChanges(draft), []);
});

test('removing a grant removes its policy edits and unsupported slots reject edits', () => {
  const baseline = row('page', true);
  let draft = setResourcePolicy({}, baseline, 'field', 'field-rule');
  draft = setResourceGrant(draft, baseline, false);
  assert.deepEqual(resourceChanges(draft), [{resourceId: 'page', resourceCode: 'code-page',
    revision: 'v1', granted: false}]);
  assert.deepEqual(setResourcePolicy({}, {...baseline, fieldPermission: {supported: false,
    policyId: null}}, 'field', 'field-rule'), {});
});

test('complete 5000-row subtree staging preserves original baselines and does not mutate its input', () => {
  const original = setResourcePolicy({}, {...row('kept', true), dataPermission: {supported: true, policyId: 'old'}}, 'data', 'new');
  const rows = [row('kept', true, 'v2'), ...Array.from({length: 4999}, (_, i) => row('bulk-' + i))];
  const draft = setResourceGrants(original, rows, true);
  assert.equal(Object.keys(draft).length, 5000);
  assert.equal(draft.kept.baseline.revision, 'v1'); assert.equal(draft.kept.dataScopeId, 'new');
  assert.equal(Object.keys(original).length, 1); assert.equal(original.kept.dataScopeId, 'new');
  assert.deepEqual(setResourceGrants({}, rows, false), {kept: {baseline: rows[0], granted: false}});
});

test('bulk staging has exactly the same undo and policy semantics as individual choices', () => {
  const rows = [row('already', true), row('new'), {...row('structure'), grantable: false}];
  const individual = rows.reduce((draft, current) => setResourceGrant(draft, current, true), {});
  assert.deepEqual(setResourceGrants({}, rows, true), individual);
  assert.deepEqual(setResourceGrants(individual, rows, false), rows.reduce((draft, current) => setResourceGrant(draft, current, false), individual));
});

test('unknown or masked Boolean capabilities never authorize a staged grant or policy edit', () => {
  assert.deepEqual(setResourceGrants({}, [{...row('masked'), grantable: '***'}, {...row('unknown'), granted: '***'}], true), {});
  assert.equal(displayedGrant({}, {...row('unknown'), granted: '***'}), false);
  assert.deepEqual(setResourcePolicy({}, {...row('unknown', true), dataPermission: {supported: '***', policyId: null}}, 'data', 'new'), {});
});
