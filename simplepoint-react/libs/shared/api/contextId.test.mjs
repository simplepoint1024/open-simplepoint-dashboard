import assert from 'node:assert/strict';
import test from 'node:test';
import {resolveApiScope, shouldAutoEnsureContextId, shouldUseTenantContext} from './contextId.ts';

// These requests must work in a new browser before App can restore a workspace.
test('authenticated account and workspace discovery have no tenant bootstrap dependency', () => {
  for (const path of ['/userinfo', '/userinfo?refresh=1',
    'https://home.simplepoint.online:8000/userinfo', '/common/tenants/current']) {
    assert.equal(resolveApiScope(path), 'global', path);
    assert.equal(shouldUseTenantContext(path), false, path);
    assert.equal(shouldAutoEnsureContextId(path), false, path);
  }
});

test('account discovery exemption does not extend to other endpoints or business requests', () => {
  for (const path of ['/userinfo/records', '/userinfo-forged', '/userinfos',
    '/common/tenants/current-profile', '/common/resources/service-routes',
    '/app/forms/records', '/unknown']) {
    assert.equal(resolveApiScope(path), 'tenant', path);
    assert.equal(shouldUseTenantContext(path), true, path);
  }
});
