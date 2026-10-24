import assert from 'node:assert/strict';
import test from 'node:test';
import {resolveHostComponent} from './hostComponent.ts';
test('explicit and historical host page identities use the same local registry', () => {
  assert.equal(resolveHostComponent('host/TenantResources'),'resources');
  assert.equal(resolveHostComponent('host/TenantPackages'),'packages');
  assert.equal(resolveHostComponent('host/TenantSettings'),'settings');
  assert.equal(resolveHostComponent('host/TenantHome','/tenant/resources'),'resources');
  assert.equal(resolveHostComponent('host/TenantHome','/tenant/packages'),'packages');
  assert.equal(resolveHostComponent('host/TenantHome','/tenant'),'settings');
  assert.equal(resolveHostComponent('common/Resources'),undefined);
  assert.equal(resolveHostComponent('host/Unknown'),undefined);
  assert.equal(resolveHostComponent('__proto__'),undefined);
});
