import test from 'node:test';
import assert from 'node:assert/strict';
import {switchAuthorizedContext} from './contextSwitch.ts';
import {ensureContextId, resolveApiScope} from './contextId.ts';

test('authorization preflight cannot overwrite active context or share a persisting request', async () => {
  const previousFetch = globalThis.fetch, previousStorage = globalThis.localStorage;
  const storage = new Map([['sp.contextId', 'active-context']]);
  const pending = [];
  globalThis.localStorage = {getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key)};
  globalThis.fetch = () => new Promise(resolve => pending.push(resolve));
  try {
    const preflight = ensureContextId('destination', {force: true, roleId: '', persist: false});
    const normal = ensureContextId('destination', {force: true, roleId: ''});
    assert.equal(pending.length, 2, 'side-effect-free preflight must not deduplicate with normal context initialization');
    pending[0](new Response(JSON.stringify('preview-context'), {headers: {'content-type': 'application/json'}}));
    assert.equal(await preflight, 'preview-context');
    assert.deepEqual([...storage], [['sp.contextId', 'active-context']]);
    pending[1](new Response(JSON.stringify('normal-context'), {headers: {'content-type': 'application/json'}}));
    assert.equal(await normal, 'normal-context');
    assert.equal(storage.get('sp.contextId:destination'), 'normal-context');
  } finally {globalThis.fetch = previousFetch; globalThis.localStorage = previousStorage;}
});

test('directory discovery does not require an already valid tenant context', () => {
  for (const path of ['/common/current-directory/tenants', '/common/current-directory/roles/selected']) assert.equal(resolveApiScope(path), 'global');
  assert.equal(resolveApiScope('/common/current-directory-forged'), 'tenant');
});
test('switching commits once, only after confirmation and successful server authorization', async () => {
  const events = [];
  assert.equal(await switchAuthorizedContext({current: () => true, unavailable: 'Unavailable',
    confirm: async () => {events.push('confirm'); return true;},
    authorize: async () => {events.push('authorize'); return 'context';}, commit: value => events.push(value)}), true);
  assert.deepEqual(events, ['confirm', 'authorize', 'context']);
});
test('cancelled and expired confirmation never authorizes or commits a destination', async () => {
  for (const cancelled of [true, false]) {
    let current = true;
    assert.equal(await switchAuthorizedContext({current: () => current, unavailable: 'Unavailable',
      confirm: async () => {current = cancelled; return !cancelled;},
      authorize: async () => assert.fail('must not authorize'), commit: () => assert.fail('must not commit')}), false);
  }
});
test('failure or a late response after another scope change never replaces the active scope', async () => {
  let current = true;
  const base = {current: () => current, confirm: async () => true, unavailable: 'Unavailable', commit: () => assert.fail('must not commit')};
  await assert.rejects(switchAuthorizedContext({...base, authorize: async () => undefined}), /Unavailable/);
  await assert.rejects(switchAuthorizedContext({...base, authorize: async () => {throw Error('Network');}}), /Network/);
  assert.equal(await switchAuthorizedContext({...base, authorize: async () => {current = false; return 'stale';}}), false);
});
