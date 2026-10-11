import assert from 'node:assert/strict';
import test from 'node:test';
import {registerWorkflowFormAdapter, workflowFormAdapter, workflowFormAdapters,
  subscribeWorkflowFormAdapters} from './adapterRegistry.ts';

test('registration, immutable stable snapshots and disposal notify consumers exactly once', () => {
  const before = workflowFormAdapters();
  assert.strictEqual(workflowFormAdapters(), before);
  let notifications = 0;
  const unsubscribe = subscribeWorkflowFormAdapters(() => notifications++);
  const adapter = {provider: 'registry-test', renderStartForm: () => null};
  const dispose = registerWorkflowFormAdapter(adapter);
  assert.deepEqual(workflowFormAdapter(adapter.provider), adapter);
  assert.notStrictEqual(workflowFormAdapter(adapter.provider), adapter);
  assert.ok(Object.isFrozen(workflowFormAdapter(adapter.provider)));
  assert.ok(Object.isFrozen(workflowFormAdapters()));
  assert.notStrictEqual(workflowFormAdapters(), before);
  assert.throws(() => registerWorkflowFormAdapter(adapter), /already registered/);
  dispose(); dispose(); unsubscribe();
  assert.equal(notifications, 2);
  assert.equal(workflowFormAdapter(adapter.provider), undefined);
});

test('independent federated module copies share one explicit registry', async () => {
  const copy = await import('./adapterRegistry.ts?federated-copy');
  const adapter = {provider: 'federation-test'};
  const dispose = copy.registerWorkflowFormAdapter(adapter);
  assert.deepEqual(workflowFormAdapter(adapter.provider), adapter);
  assert.strictEqual(copy.workflowFormAdapters(), workflowFormAdapters());
  dispose();
  assert.equal(workflowFormAdapter(adapter.provider), undefined);
});

test('invalid provider identifiers do not alter the registry', () => {
  const before = workflowFormAdapters();
  for (const provider of ['', '../external', 'https://untrusted.example/form', 'a'.repeat(81)]) {
    assert.throws(() => registerWorkflowFormAdapter({provider}), /Invalid/);
  }
  assert.strictEqual(workflowFormAdapters(), before);
});

test('caller mutations cannot alter a published provider or redirect disposal', () => {
  const render = () => null, adapter = {provider: 'immutable-provider', renderStartForm: render};
  const dispose = registerWorkflowFormAdapter(adapter);
  const snapshot = workflowFormAdapters();
  const other = registerWorkflowFormAdapter({provider: 'unrelated-provider'});
  adapter.provider = 'unrelated-provider'; adapter.renderStartForm = () => 'changed';
  assert.equal(workflowFormAdapter('immutable-provider').renderStartForm, render);
  assert.equal(snapshot.find(entry => entry.provider === 'immutable-provider').renderStartForm, render);
  dispose();
  assert.equal(workflowFormAdapter('immutable-provider'), undefined);
  assert.equal(workflowFormAdapter('unrelated-provider').provider, 'unrelated-provider');
  other();
});

test('a stale disposer cannot remove a new registration with the same provider', () => {
  const dispose = registerWorkflowFormAdapter({provider: 'replace-provider'});
  dispose();
  const replacement = registerWorkflowFormAdapter({provider: 'replace-provider'});
  dispose();
  assert.equal(workflowFormAdapter('replace-provider').provider, 'replace-provider');
  replacement();
});
