import test from 'node:test';
import assert from 'node:assert/strict';
import {tenantEndpoint, tenantLogo, tenantOption, tenantType} from './model.ts';

test('tenant selection keeps platform administration separate from current membership', () => {
  assert.equal(tenantEndpoint('platform'), '/common/platform/tenants');
  assert.equal(tenantEndpoint('current'), '/common/current-directory/tenants');
});
test('labels never expose raw or masked identities and virtual platform labels are localized', () => {
  for (const id of [null, undefined, '', ' ', '***']) assert.equal(tenantOption({id, name: 'Name'}, 'Unnamed', 'Platform'), undefined);
  assert.deepEqual(tenantOption({id: 'tenant', name: ' Name '}, 'Unnamed', 'Platform'),
    {value: 'tenant', label: 'Name', metadata: {type: undefined, logo: undefined}});
  assert.deepEqual(tenantOption({id: 'tenant', name: '***'}, 'Unnamed', 'Platform'),
    {value: 'tenant', label: 'Unnamed', metadata: {type: undefined, logo: undefined}});
  assert.deepEqual(tenantOption({id: '__platform__', name: '平台工作台', type: 'PLATFORM'}, 'Unnamed', 'Platform'),
    {value: '__platform__', label: 'Platform', metadata: {type: 'PLATFORM', logo: undefined}});
});

test('current personal workspace has a short localized label without changing its persisted identity', () => {
  const row = {id: 'personal-uuid', name: 'account-uuid 的个人空间', type: 'PERSONAL'};
  assert.deepEqual(tenantOption(row, 'Unnamed', 'Platform', '我的空间'),
    {value: row.id, label: '我的空间', metadata: {type: 'PERSONAL', logo: undefined}});
  assert.deepEqual(tenantOption(row, 'Unnamed', 'Platform', 'My Workspace'),
    {value: row.id, label: 'My Workspace', metadata: {type: 'PERSONAL', logo: undefined}});
  assert.equal(tenantOption({...row, name: undefined}, 'Unnamed', 'Platform', '我的空间').label, '我的空间');
  assert.equal(tenantOption({...row, type: 'ORGANIZATION'}, 'Unnamed', 'Platform', '我的空间').label, row.name,
    'an organization is not renamed by its name or ID spelling');
  assert.equal(tenantOption(row, 'Unnamed', 'Platform').label, row.name,
    'platform administration must not call other users’ workspaces My Workspace');
});

test('both directory contracts preserve authorized type and image metadata, not unrelated entity fields', () => {
  for (const type of ['PLATFORM', 'PERSONAL', 'ORGANIZATION']) {
    for (const key of ['type', 'tenantType']) {
      assert.deepEqual(tenantOption({id: 'workspace', name: 'Name', [key]: type,
        logo: '/common/object-storage/images/logo', ownerId: 'private', description: 'private'}, 'Unnamed', 'Platform'),
      {value: 'workspace', label: type === 'PLATFORM' ? 'Platform' : 'Name',
        metadata: {type, logo: '/common/object-storage/images/logo'}});
    }
  }
  assert.equal(tenantType('***'), undefined, 'masked types are not guessed as an organization');
  assert.equal(tenantType('UNKNOWN'), undefined);
});

test('only bounded safe image locations reach the shared renderer', () => {
  for (const safe of ['/common/object-storage/images/logo', 'https://example.test/logo.png', 'http://localhost/logo']) {
    assert.equal(tenantLogo(` ${safe} `), safe);
  }
  for (const unsafe of [null, '', '***', 'javascript:alert(1)', 'data:image/svg+xml,<svg/>',
    '//example.test/logo', '/\\example.test/logo', 'https://user:secret@example.test/logo',
    '/logo\ntracking', '/logo\u0000', '/'.repeat(1025), 'relative-token']) {
    assert.equal(tenantLogo(unsafe), undefined, String(unsafe));
  }
});
