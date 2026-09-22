import assert from 'node:assert/strict';
import test from 'node:test';
import {availableApps, emptyPreferences, parsePreferences, safePath, validTimeZone} from './preferences.ts';
import {notificationTarget} from '../../notifications/models.ts';

test('empty and persisted home settings round trip without losing inaccessible app paths', () => {
  assert.deepEqual(parsePreferences(null), emptyPreferences());
  const value = {...emptyPreferences(), region: ' Shanghai ', timeZone: 'Asia/Shanghai', groups: [{id: 'a', name: ' Work ', paths: ['/dna/sql', '/other-role', '/dna/sql']}]};
  assert.deepEqual(parsePreferences(JSON.stringify(value)), {...value, region: 'Shanghai', groups: [{id: 'a', name: 'Work', paths: ['/dna/sql', '/other-role']}]});
});
test('invalid documents are rejected, never replaced silently with empty settings', () => {
  for (const value of ['bad-json', {}, {version: 2, groups: []}, {...emptyPreferences(), timeZone: 'bad-zone'},
    {...emptyPreferences(), groups: [{id: 'a', name: '', paths: []}]},
    {...emptyPreferences(), groups: [{id: 'a', name: 'a', paths: ['//evil.test']}]},
    {...emptyPreferences(), groups: Array(13).fill({id: 'a', name: 'a', paths: []})},
    {...emptyPreferences(), groups: [{id: 'a', name: 'a', paths: []}, {id: 'a', name: 'b', paths: []}]}]) {
    assert.throws(() => parsePreferences(value));
  }
});
test('favorites use authorized routable apps only and honor tenant restrictions', () => {
  const routes = ['/ok', '/dashboard', '/hidden', '/disabled', '/org', '/external', '/ok'].map(path => ({path, component: 'dna/Console',
    routeKind: path === '/hidden' ? 'hidden' : 'item', disabled: path === '/disabled', requireOrgTenant: path === '/org'}));
  routes[5].component = 'external:https://example.com';
  assert.deepEqual(availableApps(routes, 'PERSONAL').map(r => r.path), ['/ok']);
  assert.deepEqual(availableApps(routes, 'PLATFORM').map(r => r.path), ['/ok', '/org']);
});
test('unsafe paths and invalid time zones cannot become launch targets or crash the clock', () => {
  for (const value of ['//evil.test', '/\\evil', '/a\nb', 'javascript:alert(1)', '/a?next=b', '/:id', '/a/*']) assert.equal(safePath(value), false);
  assert.equal(safePath('/dna/sql-console'), true);
  assert.equal(validTimeZone('UTC'), true);
  assert.equal(validTimeZone('missing'), false);
});
test('notification links reject executable and ambiguous URLs', () => {
  for (const value of ['javascript:alert(1)', '//evil.test', '/\\evil', 'http://example.com', 'https://user:pass@example.com', '/a\nb']) assert.equal(notificationTarget(value), undefined);
  assert.deepEqual(notificationTarget('/profile'), {url: '/profile', external: false});
  assert.deepEqual(notificationTarget('https://example.com/help'), {url: 'https://example.com/help', external: true});
});
