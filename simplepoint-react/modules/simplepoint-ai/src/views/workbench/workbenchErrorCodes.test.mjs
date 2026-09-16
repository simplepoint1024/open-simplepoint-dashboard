import assert from 'node:assert/strict';
import test from 'node:test';
import {
  localizeWorkbenchError,
  localizeWorkbenchOperationError,
  resolveWorkbenchErrorCode,
  resolveWorkbenchOperationError,
} from './workbenchErrorCodes.ts';

const fallback = {
  key: 'ai.model-debug.error.generate',
  fallback: '模型调用失败',
};

test('all workbench domains resolve structured error codes locally', () => {
  assert.equal(
    resolveWorkbenchOperationError({
      data: {errorCode: 'AI_MODEL_REQUEST_INVALID'},
    }, fallback).key,
    'ai.models.error.requestInvalid',
  );
  assert.equal(
    resolveWorkbenchOperationError({
      code: 'AI_API_KEY_OPERATION_UNAVAILABLE',
    }, fallback).key,
    'ai.api-keys.error.operationUnavailable',
  );
  assert.equal(
    resolveWorkbenchOperationError({
      data: {errorCode: 'AI_BILLING_RANGE_INVALID'},
    }, fallback).key,
    'ai.billing.error.rangeInvalid',
  );
  assert.equal(
    resolveWorkbenchOperationError({
      code: 'AI_MODEL_DEBUG_PROVIDER_FAILED',
    }, fallback).key,
    'ai.model-debug.error.providerFailed',
  );
  assert.equal(
    resolveWorkbenchOperationError({
      code: 'AI_KNOWLEDGE_REQUEST_INVALID',
    }, fallback).key,
    'ai.error.requestInvalid',
  );
  assert.equal(
    resolveWorkbenchOperationError({
      data: {errorCode: 'AI_MCP_SERVER_OPERATION_CONFLICT'},
    }, fallback).key,
    'ai.error.operationConflict',
  );
  assert.equal(
    resolveWorkbenchOperationError({
      code: 'AI_RUNTIME_NODE_FENCED',
    }, fallback).key,
    'ai.error.runtimeNodeFenced',
  );
});

test('stream error codes use the same finite local mapping', () => {
  assert.equal(
    resolveWorkbenchErrorCode(
      'AI_MODEL_DEBUG_GENERATION_FAILED',
      fallback,
    ).key,
    'ai.model-debug.error.generationFailed',
  );
});

test('unknown errors never expose server prose or raw codes', () => {
  const privateProse = 'provider credential and endpoint details';
  const unknownCode = 'UPSTREAM_PRIVATE_ERROR_CODE';
  const fromProse = resolveWorkbenchOperationError(
    new Error(privateProse),
    fallback,
  );
  const fromUnknownCode = resolveWorkbenchOperationError({
    data: {errorCode: unknownCode},
  }, fallback);

  assert.equal(fromProse, fallback);
  assert.equal(fromProse.fallback.includes(privateProse), false);
  assert.equal(fromUnknownCode, fallback);
  assert.equal(fromUnknownCode.fallback.includes(unknownCode), false);
});

test('localization consumes only the selected local descriptor', () => {
  const descriptor = resolveWorkbenchErrorCode(
    'AI_MODEL_OPERATION_FAILED',
    fallback,
  );
  assert.equal(
    localizeWorkbenchError((key) => key, descriptor),
    'ai.models.error.operationFailed',
  );
});

test('operation localization composes code resolution and translation', () => {
  assert.equal(
    localizeWorkbenchOperationError(
      (key) => key,
      {code: 'AI_MCP_PUBLICATION_REQUEST_INVALID'},
      fallback,
    ),
    'ai.error.requestInvalid',
  );
});
