import assert from 'node:assert/strict';
import test from 'node:test';
import {
  resolveProviderMessage,
  resolveProviderOperationError,
  resolveProviderStatus,
} from './messageCodes.ts';

test('provider message codes resolve to local translation keys', () => {
  assert.equal(
    resolveProviderMessage('MODEL_SYNC_SUCCEEDED')?.key,
    'ai.providers.message.modelSyncSucceeded',
  );
});

test('unknown provider message codes never become user-facing text', () => {
  const unknownCode = 'REMOTE_PROVIDER_PROSE_SHOULD_NOT_RENDER';
  const descriptor = resolveProviderMessage(unknownCode);
  assert.equal(descriptor?.key, 'ai.providers.message.unknown');
  assert.equal(descriptor?.fallback.includes(unknownCode), false);
});

test('provider status codes are localized and unknown values never render', () => {
  assert.equal(
    resolveProviderStatus('SUCCESS').key,
    'ai.providers.status.connectionSucceeded',
  );
  const unknownStatus = 'REMOTE_STATUS_PROSE_SHOULD_NOT_RENDER';
  const descriptor = resolveProviderStatus(unknownStatus);
  assert.equal(descriptor.key, 'ai.providers.status.unknown');
  assert.equal(descriptor.fallback.includes(unknownStatus), false);
});

test('structured provider operation codes resolve to local messages', () => {
  assert.equal(
    resolveProviderOperationError({
      data: {errorCode: 'AI_PROVIDER_DISABLED'},
    }).key,
    'ai.providers.error.disabled',
  );
  assert.equal(
    resolveProviderOperationError({code: 'AI_PROVIDER_NOT_FOUND'}).key,
    'ai.providers.error.notFound',
  );
  assert.equal(
    resolveProviderOperationError({
      data: {errorCode: 'AI_PROVIDER_REQUEST_INVALID'},
    }).key,
    'ai.providers.error.requestInvalid',
  );
  assert.equal(
    resolveProviderOperationError({
      data: {errorCode: 'AI_PROVIDER_OPERATION_UNAVAILABLE'},
    }).key,
    'ai.providers.error.operationUnavailable',
  );
});

test('unknown provider failures never expose server prose or raw codes', () => {
  const privateProse = 'upstream-secret-provider-prose';
  const unknownCode = 'UPSTREAM_PROVIDER_CODE_WITHOUT_TRANSLATION';
  const fromProse = resolveProviderOperationError(new Error(privateProse));
  const fromUnknownCode = resolveProviderOperationError({
    data: {errorCode: unknownCode},
  });

  assert.equal(fromProse.key, 'ai.providers.page.error.operation');
  assert.equal(fromProse.fallback.includes(privateProse), false);
  assert.equal(fromUnknownCode.key, 'ai.providers.page.error.operation');
  assert.equal(fromUnknownCode.fallback.includes(unknownCode), false);
});
