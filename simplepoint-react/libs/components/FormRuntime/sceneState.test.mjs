import assert from 'node:assert/strict';
import {test} from 'node:test';
import {canOpenScene, sceneDraft, sceneValues, mergeSceneValues, sameFormValues} from './sceneState.ts';

test('scene projections and edits preserve parent, readonly and other scene fields', () => {
  const values = {name: 'Ada', detail: 'Old', protected: 'safe', other: {nested: true}};
  assert.deepEqual(sceneValues(values, ['detail', 'protected']), {detail: 'Old', protected: 'safe'});
  const edited = mergeSceneValues(values, {detail: 'New', protected: 'forged'}, {editableFieldIds: ['detail']});
  assert.deepEqual(edited, {...values, detail: 'New'});
  const cleared = mergeSceneValues(values, {}, {editableFieldIds: ['detail']});
  assert.equal(cleared.detail, undefined);
  assert.equal(cleared.name, 'Ada');
});

test('nested drafts are isolated and cancellation cannot mutate the parent snapshot', () => {
  const parent = {details: {city: 'Tokyo'}};
  const draft = sceneDraft('address', parent);
  draft.values.details.city = 'Osaka';
  assert.equal(parent.details.city, 'Tokyo');
  assert.equal(draft.initial.details.city, 'Tokyo');
  assert.equal(sameFormValues(draft.initial, draft.values), false);
  assert.equal(sameFormValues({a: 1, b: undefined}, {a: 1}), true);
});

test('navigation refuses missing, wrong-type, recursive and over-budget scenes', () => {
  const document = {scenes: [{sceneId: 'dialog', type: 'DIALOG'}, {sceneId: 'drawer', type: 'DRAWER'}]};
  const action = {key: 'open-dialog', targetSceneId: 'dialog'};
  assert.equal(canOpenScene(document, action, ['main']), true);
  assert.equal(canOpenScene(document, action, ['main', 'dialog']), false);
  assert.equal(canOpenScene(document, {...action, targetSceneId: 'drawer'}, ['main']), false);
  assert.equal(canOpenScene(document, {...action, targetSceneId: 'absent'}, ['main']), false);
  assert.equal(canOpenScene(document, action, Array.from({length: 8}, (_, i) => String(i))), false);
});
