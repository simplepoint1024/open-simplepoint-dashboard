import assert from 'node:assert/strict';
import test from 'node:test';
import {parseRemotePreloadTarget} from './mfPreload.ts';

test('parses a remote component into its remote and expose names', () => {
  assert.deepEqual(parseRemotePreloadTarget('common/system/User'), {
    nameOrAlias: 'common',
    expose: 'system/User',
  });
  assert.deepEqual(parseRemotePreloadTarget('ai/workbench/Example/NestedPage'), {
    nameOrAlias: 'ai',
    expose: 'workbench/Example/NestedPage',
  });
});

test('ignores local, external, iframe and malformed component specs', () => {
  assert.equal(parseRemotePreloadTarget('./local'), undefined);
  assert.equal(parseRemotePreloadTarget('external:https://example.com'), undefined);
  assert.equal(parseRemotePreloadTarget('iframe:https://example.com'), undefined);
  assert.equal(parseRemotePreloadTarget('common'), undefined);
  assert.equal(parseRemotePreloadTarget('common/'), undefined);
});
