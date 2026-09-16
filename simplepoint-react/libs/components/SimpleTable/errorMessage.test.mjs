import assert from 'node:assert/strict';
import test from 'node:test';
import {resolveSimpleTableOperationError} from './errorMessage.ts';

test('uses the page resolver for actionable domain errors', () => {
  const message = resolveSimpleTableOperationError(
    {data: {errorCode: 'DOMAIN_ERROR'}},
    'add',
    (_error, action) => `${action}: localized`,
    () => 'generic',
  );
  assert.equal(message, 'add: localized');
});

test('falls back safely when a resolver is empty or fails', () => {
  assert.equal(
    resolveSimpleTableOperationError({}, 'edit', () => '', () => 'generic'),
    'generic',
  );
  assert.equal(
    resolveSimpleTableOperationError(
      {},
      'delete',
      () => {
        throw new Error('resolver bug');
      },
      () => 'generic',
    ),
    'generic',
  );
});
