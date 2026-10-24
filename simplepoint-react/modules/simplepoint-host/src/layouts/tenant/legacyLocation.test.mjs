import {test} from 'node:test';
import assert from 'node:assert/strict';
import {legacyTenantTarget} from './legacyLocation.ts';
test('legacy tab bookmarks preserve their independent resource destinations', () => {
  for (const section of ['settings', 'resources', 'packages']) assert.equal(legacyTenantTarget('/tenant?section='+section), '/tenant/'+section);
  assert.equal(legacyTenantTarget('/tenant?edit=1'), '/tenant/settings');
  for (const path of ['/tenant', '/tenant?section=overview', '/tenant?section=https://bad.invalid', '/tenant/settings']) assert.equal(legacyTenantTarget(path), undefined);
});
