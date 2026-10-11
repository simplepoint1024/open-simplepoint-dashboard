import {test} from 'node:test';
import assert from 'node:assert/strict';
import {prepareFormSubmission, claimFormSubmission, startFormWrite, cancelFormPreparation, cancelFormValidation, finishFormSubmission} from './formSubmission.ts';

test('form target is captured before validation and only one callback can claim its accepted submission', () => {
  const ref = {current: undefined}, request = new AbortController(), target = {action: 'edit', id: 'actual-id'};
  const pending = prepareFormSubmission(ref, 'owner-a', request, target);
  assert.equal(pending.phase, 'validating'); assert.equal(pending.target, target);
  assert.equal(prepareFormSubmission(ref, 'owner-a', new AbortController(), {}), undefined);
  assert.equal(claimFormSubmission(ref, 'owner-b'), undefined);
  assert.equal(claimFormSubmission(ref, 'owner-a'), pending); assert.equal(pending.phase, 'preparing');
  assert.equal(startFormWrite(ref, pending), true); assert.equal(pending.phase, 'writing');
  assert.equal(claimFormSubmission(ref, 'owner-a'), undefined);
  assert.equal(cancelFormValidation(ref, 'owner-a'), undefined, 'a late validation error cannot unlock a writer');
  assert.equal(finishFormSubmission(ref, pending), true); assert.equal(ref.current, undefined);
});
test('invalid validation releases its accepted lease and allows a later valid attempt', () => {
  const ref = {current: undefined};
  const invalid = prepareFormSubmission(ref, 'owner', new AbortController(), {value: 'invalid'});
  assert.equal(cancelFormValidation(ref, 'owner'), invalid); assert.equal(cancelFormValidation(ref, 'owner'), undefined);
  const valid = prepareFormSubmission(ref, 'owner', new AbortController(), {value: 'valid'});
  assert.equal(claimFormSubmission(ref, 'owner'), valid); assert.equal(valid.phase, 'preparing');
});
test('an old scope/attempt cannot cancel, claim or release a new owner and aborted validation cannot write', () => {
  const ref = {current: undefined}, request = new AbortController();
  const old = prepareFormSubmission(ref, 'old', request, {}); request.abort();
  assert.equal(claimFormSubmission(ref, 'old'), undefined); assert.equal(cancelFormValidation(ref, 'old'), old);
  const current = prepareFormSubmission(ref, 'new', new AbortController(), {});
  assert.equal(cancelFormValidation(ref, 'old'), undefined); assert.equal(claimFormSubmission(ref, 'old'), undefined);
  assert.equal(finishFormSubmission(ref, old), false); assert.equal(ref.current, current);
  assert.equal(prepareFormSubmission({current: undefined}, 'owner', request, {}), undefined);
});
test('leaving cancels asynchronous preparation but never an already dispatched write', () => {
  const ref = {current: undefined}, request = new AbortController();
  const preparing = prepareFormSubmission(ref, 'owner', request, {}); claimFormSubmission(ref, 'owner');
  assert.equal(cancelFormPreparation(ref, 'other'), undefined);
  assert.equal(cancelFormPreparation(ref, 'owner'), preparing); assert.equal(request.signal.aborted, true);
  assert.equal(startFormWrite(ref, preparing), false);
  const writer = prepareFormSubmission(ref, 'owner', new AbortController(), {}); claimFormSubmission(ref, 'owner');
  assert.equal(startFormWrite(ref, writer), true); assert.equal(cancelFormPreparation(ref, 'owner'), undefined);
  assert.equal(writer.request.signal.aborted, false);
  assert.equal(finishFormSubmission(ref, preparing), false); assert.equal(ref.current, writer);
});
