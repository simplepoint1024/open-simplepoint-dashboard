import assert from 'node:assert/strict';
import {test} from 'node:test';
import {projectedReference, referenceLabelFor} from './model.ts';
import {displayOnlyProjection} from '../SForm/displayOnly.ts';

test('labels bind to an exact visible identity and never replace masked or missing values', () => {
  const projection = {id: 'actor', label: '员工・日本語😀'};
  assert.deepEqual(projectedReference('actor', projection), projection);
  for (const value of ['***', 'other', null, undefined, '', {}]) assert.equal(projectedReference(value, projection), undefined);
  assert.deepEqual(projectedReference('actor', {id: 'actor', label: null}), {id: 'actor', label: null});
  assert.equal(projectedReference('actor', {id: 'actor', label: 'x'.repeat(257)}), undefined);
  assert.equal(projectedReference('actor', Object.create(projection)), undefined);
});

test('shared schema projection keeps response labels, while rows keep original IDs', () => {
  const projection = {id: 'actor', label: '员工'};
  const row = {createdBy: 'actor', $displayLabels: {createdBy: projection}};
  assert.equal(referenceLabelFor(row, '$displayLabels', 'createdBy'), projection);
  assert.equal(referenceLabelFor(row, 'prototype', 'createdBy'), undefined);
  assert.equal(referenceLabelFor(Object.create(row), '$displayLabels', 'createdBy'), undefined);
  const source = {type: 'string', 'x-display-only': true, 'x-reference-label': projection};
  assert.equal(displayOnlyProjection(source).schema['x-reference-label'], projection);
  assert.equal(row.createdBy, 'actor');
});
