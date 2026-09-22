import assert from 'node:assert/strict';
import test from 'node:test';
import {resolveSimpleTableLoadError, resolveSimpleTableOperationError} from './errorMessage.ts';

test('list failures expose controlled query validation but never internal SQL or arbitrary exceptions', () => {
  const invalid = {status: 400, data: {code: 'INVALID_QUERY', message: '筛选值与字段类型不匹配'}};
  assert.equal(resolveSimpleTableLoadError(invalid, 'generic'), invalid.data.message);
  for (const error of [undefined, Error('SELECT secret'), {...invalid, status: 500},
    {status: 400, data: {message: 'internal SQL'}}, {...invalid, data: {...invalid.data, message: 'x'.repeat(201)}}]) {
    assert.equal(resolveSimpleTableLoadError(error, 'generic'), 'generic');
  }
});

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
