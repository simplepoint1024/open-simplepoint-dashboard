import assert from 'node:assert/strict';
import {test} from 'node:test';
import {rememberWorkspace, rememberedWorkspace, workspaceUserId, selectInitialWorkspace, clearStorageKeepingWorkspaceChoices} from './workspace.ts';
import {clearClientCaches, redirectToLogout} from '../api/session.ts';

function storage() {
  const values = new Map();
  return {getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value),
    clear: () => values.clear(), removeItem: key => values.delete(key)};
}
async function withStorage(run) {
  const keys = ['localStorage', 'sessionStorage', 'document'];
  const previous = keys.map(key => Object.getOwnPropertyDescriptor(globalThis, key));
  Object.defineProperty(globalThis, 'localStorage', {configurable: true, value: storage()});
  Object.defineProperty(globalThis, 'sessionStorage', {configurable: true, value: storage()});
  try {await run();} finally {
    keys.forEach((key, index) => previous[index] ? Object.defineProperty(globalThis, key, previous[index]) : delete globalThis[key]);
  }
}

test('logout keeps account choices but erases authorization context, session identity and business caches', () => withStorage(async () => {
  rememberWorkspace('alice', 'second'); rememberWorkspace('bob', 'third');
  localStorage.setItem('sp.tenantId', 'second'); localStorage.setItem('sp.contextId:second', 'authorization');
  localStorage.setItem('business.draft', 'private'); sessionStorage.setItem('sp.userinfo', '{"sub":"alice"}');
  let submitted = false;
  const form = {style: {}, requestSubmit() {submitted = true;}};
  Object.defineProperty(globalThis, 'document', {configurable: true, value: {createElement: () => form, body: {appendChild() {}}}});
  await redirectToLogout();
  assert(submitted); assert.equal(form.method, 'post'); assert.equal(form.action, '/logout');
  assert.equal(rememberedWorkspace('alice'), 'second'); assert.equal(rememberedWorkspace('bob'), 'third');
  assert.equal(rememberedWorkspace('new-user'), undefined);
  for (const key of ['sp.tenantId', 'sp.contextId:second', 'business.draft']) assert.equal(localStorage.getItem(key), null);
  assert.equal(sessionStorage.getItem('sp.userinfo'), null);
  assert.equal(selectInitialWorkspace(rememberedWorkspace('alice'), undefined, ['first', 'second']), 'second');
}));

test('session expiry cleanup also retains choices without preserving credentials', () => withStorage(async () => {
  rememberWorkspace('alice', 'second'); localStorage.setItem('sp.contextId', 'authorization');
  await clearClientCaches();
  assert.equal(rememberedWorkspace('alice'), 'second'); assert.equal(localStorage.getItem('sp.contextId'), null);
}));

test('revoked workspaces fall back to current membership and empty memberships never restore stale choices', () => {
  assert.equal(selectInitialWorkspace('revoked', 'legacy', ['first', 'legacy']), 'first');
  assert.equal(selectInitialWorkspace('revoked', undefined, []), undefined);
  assert.equal(selectInitialWorkspace(undefined, 'existing-session', ['first', 'existing-session']), 'existing-session');
});

test('anonymous, malformed and authorization-shaped values are not retained as preferences', () => withStorage(async () => {
  rememberWorkspace(undefined, 'second'); assert.equal(localStorage.getItem('sp.preference.workspace.v1'), null);
  assert.equal(workspaceUserId({sub: 'alice'}), 'alice'); assert.equal(workspaceUserId({username: 'anonymous'}), undefined);
  localStorage.setItem('sp.preference.workspace.v1', '{"alice":"second","bad":{"contextId":"secret"}}');
  clearStorageKeepingWorkspaceChoices();
  assert.deepEqual(JSON.parse(localStorage.getItem('sp.preference.workspace.v1')), {alice: 'second'});
}));
