import assert from 'node:assert/strict';
import test from 'node:test';
import {
  resolveCatalogItemStatus,
  resolveCatalogOperationError,
  resolveCatalogSyncMode,
  resolveCatalogSyncNotice,
  resolveCatalogSyncStatus,
} from './syncErrorCodes.ts';

test('catalog sync codes resolve to local translation keys', () => {
  assert.deepEqual(
    resolveCatalogSyncNotice('OFFICIAL_MCP_SYNC_LOCK_BUSY'),
    {
      key: 'ai.catalog.warning.syncLockBusy',
      fallback: '另一个官方 Registry 同步任务正在运行，请稍后重试',
      tone: 'warning',
    },
  );
});

test('catalog item lifecycle values are localized with a safe unknown value', () => {
  assert.deepEqual(
    ['ACTIVE', 'READY', 'DRAFT', 'ERROR', 'DISABLED', 'DELETED'].map(
      (status) => resolveCatalogItemStatus(status).key,
    ),
    [
      'ai.catalog.itemStatus.active',
      'ai.catalog.itemStatus.ready',
      'ai.catalog.itemStatus.draft',
      'ai.catalog.itemStatus.error',
      'ai.catalog.itemStatus.disabled',
      'ai.catalog.itemStatus.deleted',
    ],
  );
  const privateStatus = 'PRIVATE_UPSTREAM_STATE';
  const resolved = resolveCatalogItemStatus(privateStatus);
  assert.equal(resolved.key, 'ai.catalog.itemStatus.unknown');
  assert.equal(resolved.fallback.includes(privateStatus), false);
});

test('catalog operation errors use structured codes without server prose', () => {
  assert.equal(
    resolveCatalogOperationError(
      {data: {errorCode: 'AI_CATALOG_REQUEST_INVALID'}},
      {key: 'fallback', fallback: 'fallback'},
    ).key,
    'ai.catalog.error.requestInvalid',
  );

  const privateProse = 'database topology and registry credentials';
  const resolved = resolveCatalogOperationError(
    {data: {errorCode: privateProse}},
    {key: 'ai.catalog.error.import', fallback: 'MCP Server 导入失败'},
  );
  assert.equal(resolved.key, 'ai.catalog.error.import');
  assert.equal(resolved.fallback.includes(privateProse), false);
});

test('unknown catalog sync codes never become user-facing text', () => {
  const unknownCode = 'UPSTREAM_ENGLISH_PROSE_SHOULD_NOT_RENDER';
  const notice = resolveCatalogSyncNotice(unknownCode);
  assert.equal(notice?.key, 'ai.catalog.error.syncUnknown');
  assert.equal(notice?.tone, 'error');
  assert.equal(notice?.fallback.includes(unknownCode), false);
});

test('catalog status and mode codes are localized with safe unknown values', () => {
  assert.equal(
    resolveCatalogSyncStatus('SUCCEEDED').key,
    'ai.catalog.status.succeeded',
  );
  assert.equal(
    resolveCatalogSyncMode('INCREMENTAL').key,
    'ai.catalog.mode.incremental',
  );

  const unknownStatus = 'UPSTREAM_STATUS_PROSE';
  const unknownMode = 'UPSTREAM_MODE_PROSE';
  const status = resolveCatalogSyncStatus(unknownStatus);
  const mode = resolveCatalogSyncMode(unknownMode);
  assert.equal(status.key, 'ai.catalog.status.unknown');
  assert.equal(mode.key, 'ai.catalog.mode.unknown');
  assert.equal(status.fallback.includes(unknownStatus), false);
  assert.equal(mode.fallback.includes(unknownMode), false);
});
