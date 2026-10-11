import test from 'node:test';
import assert from 'node:assert/strict';
import {QueryClient, QueryObserver} from '@tanstack/react-query';
import {readFileSync} from 'node:fs';
import {permissionCatalogKey, permissionCatalogSeed} from './permissionCatalog.ts';
import {scopedQueryKey} from './queryScope.ts';
const scope = [null, null, null], otherScope = ['other-tenant', null, null];
const actual = JSON.parse(readFileSync(new URL('../../../scripts/data-scope-contracts/catalog.json', import.meta.url), 'utf8'));
const target = 'organizations.view';

test('native target query reuses the actual full authorized snapshot and its original freshness', async t => {
  const client = new QueryClient({defaultOptions: {queries: {retry: false}}}); t.after(() => client.clear());
  const time = Date.now(); client.setQueryData(scopedQueryKey(scope, permissionCatalogKey), actual, {updatedAt: time});
  const seed = permissionCatalogSeed(client, scope, target);
  assert.equal(seed.initialData[target], actual[target]); assert.equal(seed.initialDataUpdatedAt, time);
  let calls = 0;
  const observer = new QueryObserver(client, {queryKey: scopedQueryKey(scope, [...permissionCatalogKey, target]),
    queryFn: async () => {calls++; return actual;}, staleTime: 30000, ...seed});
  const unsubscribe = observer.subscribe(() => {}); t.after(unsubscribe);
  assert.equal(observer.getCurrentResult().isStale, false); assert.equal(calls, 0);
  assert.deepEqual(Object.keys(observer.getCurrentResult().data), [target]);
});
test('expired snapshot does not receive a new thirty-second lease when a field opens', async t => {
  const client = new QueryClient({defaultOptions: {queries: {retry: false}}}); t.after(() => client.clear());
  const time = Date.now() - 45000; client.setQueryData(scopedQueryKey(scope, permissionCatalogKey), actual, {updatedAt: time});
  const seed = permissionCatalogSeed(client, scope, target); assert.equal(seed.initialDataUpdatedAt, time);
  let calls = 0;
  const observer = new QueryObserver(client, {queryKey: scopedQueryKey(scope, [...permissionCatalogKey, target]),
    queryFn: async () => {calls++; return actual;}, staleTime: 30000, ...seed});
  const unsubscribe = observer.subscribe(() => {}); t.after(unsubscribe);
  await observer.refetch({cancelRefetch: false}); assert.equal(calls, 1);
});
test('different authorization, missing/empty targets and failed whole directories cannot supply a fake seed', t => {
  const client = new QueryClient(); t.after(() => client.clear());
  client.setQueryData(scopedQueryKey(scope, permissionCatalogKey), actual);
  client.setQueryData(scopedQueryKey(otherScope, permissionCatalogKey), actual);
  for (const name of [undefined, '', 'foreign.view', 'toString']) assert.deepEqual(permissionCatalogSeed(client, scope, name), {});
  assert.deepEqual(permissionCatalogSeed(client, otherScope, target), {});
  client.getQueryCache().find({queryKey: scopedQueryKey(scope, permissionCatalogKey), exact: true}).setState({status: 'error'});
  assert.deepEqual(permissionCatalogSeed(client, scope, target), {});
});
