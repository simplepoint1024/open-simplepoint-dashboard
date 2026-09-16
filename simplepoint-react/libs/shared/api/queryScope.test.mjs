import assert from 'node:assert/strict';
import test from 'node:test';
import {setImmediate} from 'node:timers/promises';
import {isCancelledError, QueryClient, QueryObserver} from '@tanstack/react-query';
import {setStoredContextId, setStoredRoleId, setStoredTenantId} from './contextId.ts';
import {bindQueryScope, getQueryScopeSnapshot, scopedQueryKey, scopedQueryOptions, subscribeQueryScope} from './queryScope.ts';

const scopeA = ['tenant-a', 'role-a', 'context-a'];
const scopeB = ['tenant-b', 'role-b', 'context-b'];
const resourceKey = ['skill-designer-mcp-servers'];

function setup(t) {
  const descriptors = new Map(['window', 'localStorage'].map((name) => [name, Object.getOwnPropertyDescriptor(globalThis, name)]));
  const storage = new Map();
  Object.defineProperty(globalThis, 'window', {configurable: true, value: new EventTarget()});
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key) => storage.get(key) ?? null,
      setItem: (key, value) => storage.set(key, value),
      removeItem: (key) => storage.delete(key),
    },
  });
  const client = new QueryClient({defaultOptions: {queries: {retry: false, gcTime: Infinity}}});
  const activate = ([tenant, role, context], event = 'sp-set-tenant') => {
    setStoredTenantId(tenant ?? undefined);
    setStoredRoleId(role ?? undefined, tenant ?? undefined);
    setStoredContextId(context ?? undefined, tenant ?? undefined, role ?? undefined);
    window.dispatchEvent(new Event(event));
  };
  activate(scopeA);
  t.after(() => {
    client.clear();
    descriptors.forEach((descriptor, name) => {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else delete globalThis[name];
    });
  });
  return {client, activate};
}

for (const [dimension, next] of [
  ['tenant', ['tenant-b', 'role-a', 'context-a']],
  ['role', ['tenant-a', 'role-b', 'context-a']],
  ['context', ['tenant-a', 'role-a', 'context-b']],
]) {
  test(`cache separates ${dimension}, even for infinitely fresh data and identical resource IDs`, async (t) => {
    const {client, activate} = setup(t);
    const key = ['skill-designer-mcp-snapshot', 'same-server', 'same-snapshot'];
    await client.fetchQuery({...scopedQueryOptions(scopeA, key, async () => 'A'), staleTime: Infinity});
    activate(next);
    assert.equal(client.getQueryData(scopedQueryKey(next, key)), undefined);
    assert.equal(await client.fetchQuery({...scopedQueryOptions(next, key, async () => 'B'), staleTime: Infinity}), 'B');
    assert.equal(client.getQueryData(scopedQueryKey(scopeA, key)), 'A');
    assert.equal(client.getQueryData(scopedQueryKey(next, key)), 'B');
  });
}

test('same scope and resource reuse the cache', async (t) => {
  const {client} = setup(t);
  let calls = 0;
  const options = {...scopedQueryOptions(scopeA, resourceKey, async () => ++calls), staleTime: Infinity};
  assert.equal(await client.fetchQuery(options), 1);
  assert.equal(await client.fetchQuery(options), 1);
  assert.equal(calls, 1);
});

test('switch aborts the old signal, removes scoped caches and retains global queries', async (t) => {
  const {client, activate} = setup(t);
  const unbind = bindQueryScope(client);
  t.after(unbind);
  client.setQueryData(['userinfo'], {name: 'current user'});
  client.setQueryData(scopedQueryKey(scopeA, ['draft']), 'old draft');
  let oldSignal;
  let finish;
  const pending = client.fetchQuery(scopedQueryOptions(scopeA, resourceKey, ({signal}) => {
    oldSignal = signal;
    return new Promise((resolve) => {finish = resolve;});
  })).catch((error) => error);
  activate(scopeB);
  assert.equal(oldSignal.aborted, true);
  assert.ok(isCancelledError(await pending));
  assert.equal(client.getQueryData(scopedQueryKey(scopeA, ['draft'])), undefined);
  assert.deepEqual(client.getQueryData(['userinfo']), {name: 'current user'});
  client.setQueryData(scopedQueryKey(scopeB, resourceKey), 'B');
  // Simulate a transport that ignores abort and responds after the switch.
  finish('late A');
  await setImmediate();
  assert.equal(client.getQueryData(scopedQueryKey(scopeA, resourceKey)), undefined);
  assert.equal(client.getQueryData(scopedQueryKey(scopeB, resourceKey)), 'B');
});

test('rapid A -> B -> A does not remove the newly created A cache', async (t) => {
  const {client, activate} = setup(t);
  t.after(bindQueryScope(client));
  client.setQueryData(scopedQueryKey(scopeA, resourceKey), 'old A');
  activate(scopeB);
  client.setQueryData(scopedQueryKey(scopeB, resourceKey), 'B');
  activate(scopeA);
  client.setQueryData(scopedQueryKey(scopeA, resourceKey), 'new A');
  await setImmediate();
  assert.equal(client.getQueryData(scopedQueryKey(scopeA, resourceKey)), 'new A');
  assert.equal(client.getQueryData(scopedQueryKey(scopeB, resourceKey)), undefined);
});

test('stale query closures cannot fetch using the newly selected scope', async (t) => {
  const {client, activate} = setup(t);
  let calls = 0;
  const oldOptions = scopedQueryOptions(scopeA, resourceKey, async () => ++calls);
  activate(scopeB);
  await assert.rejects(client.fetchQuery(oldOptions), isCancelledError);
  assert.equal(calls, 0);
});

test('observer changing keys never exposes previous-scope data', async (t) => {
  const {client, activate} = setup(t);
  client.setQueryData(scopedQueryKey(scopeA, resourceKey), 'A');
  const observer = new QueryObserver(client, {
    ...scopedQueryOptions(scopeA, resourceKey, async () => 'A'),
    staleTime: Infinity,
  });
  const unsubscribe = observer.subscribe(() => {});
  t.after(unsubscribe);
  assert.equal(observer.getCurrentResult().data, 'A');
  activate(scopeB);
  observer.setOptions({...scopedQueryOptions(scopeB, resourceKey, async () => 'B'), enabled: false});
  assert.equal(observer.getCurrentResult().data, undefined);
  await observer.refetch();
  assert.equal(observer.getCurrentResult().data, 'B');
});

test('writes and invalidation use the captured scope, not another workspace', async (t) => {
  const {client, activate} = setup(t);
  const key = ['ai-skill-designer-draft', 'same-skill'];
  client.setQueryData(scopedQueryKey(scopeA, key), 'A');
  activate(scopeB);
  client.setQueryData(scopedQueryKey(scopeB, key), 'B');
  client.setQueryData(scopedQueryKey(scopeA, key), 'late save A');
  await client.invalidateQueries({queryKey: scopedQueryKey(scopeA, key)});
  assert.equal(client.getQueryData(scopedQueryKey(scopeB, key)), 'B');
  assert.equal(client.getQueryState(scopedQueryKey(scopeB, key)).isInvalidated, false);
  assert.equal(client.getQueryState(scopedQueryKey(scopeA, key)).isInvalidated, true);
});

test('role, context, cross-tab storage and logout events discard old scopes', (t) => {
  const {client, activate} = setup(t);
  t.after(bindQueryScope(client));
  let previous = scopeA;
  for (const [event, next] of [
    ['sp-set-role', ['tenant-a', 'role-b', 'context-b']],
    ['sp-set-context-id', ['tenant-a', 'role-b', 'context-c']],
    ['storage', scopeB],
    ['sp-set-tenant', [null, null, null]],
  ]) {
    client.setQueryData(scopedQueryKey(previous, resourceKey), 'previous');
    activate(next, event);
    assert.equal(client.getQueryData(scopedQueryKey(previous, resourceKey)), undefined);
    assert.equal(getQueryScopeSnapshot(), JSON.stringify(next));
    previous = next;
  }
});

test('unrelated events do not notify, and subscriptions can be removed', (t) => {
  setup(t);
  let notifications = 0;
  const unsubscribe = subscribeQueryScope(() => notifications++);
  window.dispatchEvent(new Event('storage'));
  setStoredContextId('background-context', 'another-tenant', 'another-role');
  window.dispatchEvent(new Event('sp-set-context-id'));
  assert.equal(notifications, 0);
  setStoredContextId('changed', 'tenant-a', 'role-a');
  window.dispatchEvent(new Event('sp-set-context-id'));
  assert.equal(notifications, 1);
  unsubscribe();
  setStoredContextId('changed-again', 'tenant-a', 'role-a');
  window.dispatchEvent(new Event('sp-set-context-id'));
  assert.equal(notifications, 1);
});
