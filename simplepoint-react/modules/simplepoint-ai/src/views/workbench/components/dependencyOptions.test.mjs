import assert from 'node:assert/strict';
import test from 'node:test';
import {
  chunkDependencyIds,
  collectDependencySelectionIssues,
  dependencyOptionKey,
  dependencyOptionValue,
  dependencySelectionKey,
  isDependencyAbortError,
  mergeDependencyOptions,
  normalizeDependencyPage,
  normalizeDependencyQuery,
} from './dependencyOptions.ts';

const option = (overrides = {}) => ({
  kind: 'SKILL',
  resourceId: 'skill-a',
  resourceCode: 'summarize',
  resourceName: 'Summarize',
  resourceVersionId: 'version-a',
  resourceVersion: '1.0.0',
  scopeType: 'SYSTEM',
  publishedAt: '2026-08-08T00:00:00Z',
  selectable: true,
  availabilityCode: null,
  ...overrides,
});

test('uses model resource IDs and immutable version IDs as option values', () => {
  assert.equal(dependencyOptionValue(option()), 'version-a');
  assert.equal(dependencyOptionKey(option()), 'SKILL:version-a');
  assert.equal(dependencyOptionValue(option({
    kind: 'MODEL',
    resourceId: 'model-a',
    resourceVersionId: null,
  })), 'model-a');
});

test('normalizes search text and enforces the 128 character request limit', () => {
  assert.equal(normalizeDependencyQuery('  alpha   beta  '), 'alpha beta');
  assert.equal(normalizeDependencyQuery('x'.repeat(256)).length, 128);
});

test('deduplicates resolve IDs and always chunks at no more than 50', () => {
  const ids = Array.from({length: 101}, (_, index) => `id-${index}`);
  ids.splice(50, 0, 'id-0', ' ', null);
  const chunks = chunkDependencyIds(ids, 200);
  assert.deepEqual(chunks.map(chunk => chunk.length), [50, 50, 1]);
  assert.equal(new Set(chunks.flat()).size, 101);
});

test('normalizes nested and flat Spring page metadata', () => {
  assert.deepEqual(normalizeDependencyPage({
    content: [option()],
    page: {size: 20, totalElements: 41, totalPages: 3, number: 1},
  }).page, {size: 20, totalElements: 41, totalPages: 3, number: 1});

  assert.deepEqual(normalizeDependencyPage({
    content: [option()],
    size: 10,
    totalElements: 12,
    totalPages: 2,
    number: 0,
  }).page, {size: 10, totalElements: 12, totalPages: 2, number: 0});
});

test('merges pages by kind and selection ID while retaining the latest projection', () => {
  const merged = mergeDependencyOptions(
    [option({resourceName: 'Old'}), option({resourceVersionId: 'version-b'})],
    [option({resourceName: 'New'})],
  );
  assert.equal(merged.length, 2);
  assert.equal(merged.find(item => item.resourceVersionId === 'version-a')?.resourceName, 'New');
});

test('keeps resolving distinct from unresolved and reports every blocking state', () => {
  const options = new Map([
    [dependencySelectionKey('SKILL', 'disabled'), option({
      resourceVersionId: 'disabled',
      selectable: false,
      availabilityCode: 'DEPENDENCY_DISABLED',
    })],
  ]);
  const resolutions = new Map([
    [dependencySelectionKey('SKILL', 'loading'), {status: 'loading'}],
    [dependencySelectionKey('SKILL', 'failed'), {status: 'error'}],
    [dependencySelectionKey('SKILL', 'missing'), {status: 'unresolved'}],
    [dependencySelectionKey('SKILL', 'disabled'), {status: 'resolved'}],
  ]);

  assert.deepEqual(
    collectDependencySelectionIssues(
      'SKILL',
      ['loading', 'failed', 'missing', 'disabled'],
      options,
      resolutions,
    ),
    [
      {id: 'loading', reason: 'RESOLVING'},
      {id: 'failed', reason: 'RESOLVE_FAILED'},
      {id: 'missing', reason: 'UNRESOLVED'},
      {
        id: 'disabled',
        reason: 'NOT_SELECTABLE',
        availabilityCode: 'DEPENDENCY_DISABLED',
      },
    ],
  );
});

test('recognizes native and shared-client abort errors', () => {
  assert.equal(isDependencyAbortError({name: 'AbortError'}), true);
  assert.equal(isDependencyAbortError({kind: 'abort'}), true);
  assert.equal(isDependencyAbortError(new Error('network')), false);
});
