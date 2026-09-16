import assert from 'node:assert/strict';
import test from 'node:test';
import {modelTypeLabel, resourceScopeLabel} from './modelLabels.ts';

const keyOf = (key) => key;

test('all supported model types resolve through ai-models resources', () => {
  const types = [
    'LLM',
    'EMBEDDING',
    'RERANK',
    'IMAGE',
    'AUDIO',
    'MODERATION',
    'MULTIMODAL',
    'OTHER',
  ];
  assert.deepEqual(
    types.map((type) => modelTypeLabel(keyOf, type)),
    types.map((type) => `ai.models.type.${type}`),
  );
});

test('unknown model types never become user-facing protocol text', () => {
  assert.equal(
    modelTypeLabel(keyOf, 'PRIVATE_PROVIDER_TYPE'),
    'ai.models.type.UNKNOWN',
  );
});

test('resource scopes use finite local labels', () => {
  assert.deepEqual(
    ['SYSTEM', 'TENANT'].map((scope) => resourceScopeLabel(keyOf, scope)),
    ['ai.scope.SYSTEM', 'ai.scope.TENANT'],
  );
  assert.equal(
    resourceScopeLabel(keyOf, 'PRIVATE_SCOPE'),
    'ai.scope.UNKNOWN',
  );
});
