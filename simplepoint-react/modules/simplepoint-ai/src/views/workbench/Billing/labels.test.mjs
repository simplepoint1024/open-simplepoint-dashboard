import assert from 'node:assert/strict';
import test from 'node:test';
import {
  invocationBillingStatusTag,
  invocationOperationLabel,
  invocationStatusTag,
} from './labels.ts';

const keyOf = (key) => key;

test('known invocation values resolve through finite local keys', () => {
  assert.deepEqual(
    ['SUCCEEDED', 'FAILED', 'RUNNING', 'CANCELLED'].map(
      (value) => invocationStatusTag(keyOf, value).label,
    ),
    [
      'ai.invocations.status.SUCCEEDED',
      'ai.invocations.status.FAILED',
      'ai.invocations.status.RUNNING',
      'ai.invocations.status.CANCELLED',
    ],
  );
  assert.deepEqual(
    ['EMBEDDING', 'GENERATION', 'RERANK'].map(
      (value) => invocationOperationLabel(keyOf, value),
    ),
    [
      'ai.invocations.operation.EMBEDDING',
      'ai.invocations.operation.GENERATION',
      'ai.invocations.operation.RERANK',
    ],
  );
  assert.deepEqual(
    ['CALCULATED', 'UNPRICED', 'NOT_CHARGED', 'PENDING'].map(
      (value) => invocationBillingStatusTag(keyOf, value).label,
    ),
    [
      'ai.invocations.billingStatus.CALCULATED',
      'ai.invocations.billingStatus.UNPRICED',
      'ai.invocations.billingStatus.NOT_CHARGED',
      'ai.invocations.billingStatus.PENDING',
    ],
  );
});

test('unknown invocation protocol values never become user-facing text', () => {
  const privateValue = 'PRIVATE_BACKEND_ENUM';
  assert.equal(
    invocationStatusTag(keyOf, privateValue).label,
    'ai.invocations.status.UNKNOWN',
  );
  assert.equal(
    invocationOperationLabel(keyOf, privateValue),
    'ai.invocations.operation.UNKNOWN',
  );
  assert.equal(
    invocationBillingStatusTag(keyOf, privateValue).label,
    'ai.invocations.billingStatus.UNKNOWN',
  );
});
