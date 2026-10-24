import test from 'node:test';
import assert from 'node:assert/strict';
import {mergeReleaseProgress, publicationPercent} from './publicationProgress.ts';

const progress = (sequence = '1', phase = 'STAGING', completedRecords = '5', totalRecords = '10') =>
  ({contractVersion: 1, sequence, phase, completedRecords, totalRecords});
const release = value => ({pageBinding: {pageId: 'page', formId: 'form', formVersion: 1, status: 'ACTIVATING'}, dataPublication: null, progress: value});

test('progress keeps exact large counters and treats 100 percent as a nonterminal phase', () => {
  assert.equal(publicationPercent(progress('9223372036854775807', 'APPLYING', '9007199254740993', '9007199254740993')), 100);
  assert.equal(publicationPercent(progress('1', 'STAGING', '0', '0')), 0);
  const result = mergeReleaseProgress(undefined, release(progress('1', 'APPLYING', '10', '10')));
  assert.equal(result.pageBinding.status, 'ACTIVATING');
  for (const invalid of [progress('0'), progress('1', 'UNKNOWN'), progress('1', 'STAGING', '11'), progress('1', 'STAGING', '01'),
    progress('1', 'STAGING', '9223372036854775808', '9223372036854775808')]) assert.throws(() => publicationPercent(invalid));
});

test('late acknowledgements and executor contention cannot roll progress back or replace an equal sequence', () => {
  const prior = release(progress('5', 'APPLYING'));
  assert.deepEqual(mergeReleaseProgress(prior, release(null)).progress, prior.progress);
  assert.deepEqual(mergeReleaseProgress(prior, release(progress('4'))).progress, prior.progress);
  assert.throws(() => mergeReleaseProgress(prior, release(progress('5', 'RESTORING'))));
  assert.equal(mergeReleaseProgress(prior, release(progress('6', 'RESTORING'))).progress.phase, 'RESTORING');
});

test('confirmed termination and another immutable version clear prior counters', () => {
  const prior = release(progress());
  for (const status of ['ACTIVE', 'CANCELLED', 'RETIRED']) {
    assert.equal(mergeReleaseProgress(prior, {...release(null), pageBinding: {...prior.pageBinding, status}}).progress, undefined);
  }
  assert.equal(mergeReleaseProgress(prior, {...release(null), pageBinding: {...prior.pageBinding, formVersion: 2}}).progress, null);
});
