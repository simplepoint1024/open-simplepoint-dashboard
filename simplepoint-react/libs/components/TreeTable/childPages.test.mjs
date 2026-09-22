import test from 'node:test';
import assert from 'node:assert/strict';
import {createChildPages} from './childPages.ts';

const deferred = () => {let resolve, reject; const promise = new Promise((yes, no) => {resolve = yes; reject = no;}); return {promise, resolve, reject};};

test('child pages are lazy, bounded, deduplicated and stop after the server last page', async () => {
  let state;
  const calls = [];
  const pager = createChildPages(async (_row, page) => {
    calls.push(page);
    return {content: Array.from({length: 50}, (_, n) => ({id: 49 * page + n})), hasNext: page < 2};
  }, row => row.id, value => {state = value;});
  assert.deepEqual(calls, []);
  await pager.next({id: 'parent'});
  assert.equal(state.get('parent').rows.length, 50);
  await pager.next({id: 'parent'});
  await pager.next({id: 'parent'});
  await pager.next({id: 'parent'});
  assert.deepEqual(calls, [0, 1, 2]);
  assert.equal(state.get('parent').rows.length, 148);
});

test('child page failure keeps loaded rows and retries the same page; repeated clicks are locked', async () => {
  let state;
  const request = deferred();
  let attempts = 0;
  const pages = [];
  const pager = createChildPages(async (_row, page) => {
    pages.push(page); attempts++;
    if (attempts === 2) return request.promise;
    return {content: [{id: page}], hasNext: true};
  }, row => row.id, value => {state = value;});
  await pager.next({id: 'a'});
  const loading = pager.next({id: 'a'});
  await pager.next({id: 'a'});
  assert.equal(attempts, 2);
  request.reject(new Error('offline')); await loading;
  assert.equal(state.get('a').failed, true);
  assert.deepEqual(state.get('a').rows, [{id: 0}]);
  await pager.next({id: 'a'});
  assert.deepEqual(pages, [0, 1, 1]);
  assert.equal(state.get('a').failed, false);
});

test('query reset aborts old requests and stale completion cannot clear the new lock or rows', async () => {
  const pending = [];
  let state;
  const pager = createChildPages((_row, page, signal) => {
    const request = deferred(); pending.push({...request, signal, page}); return request.promise;
  }, row => row.id, value => {state = value;});
  const old = pager.next({id: 'same'});
  pager.reset();
  assert.equal(pending[0].signal.aborted, true);
  const current = pager.next({id: 'same'});
  pending[0].resolve({content: [{id: 'old'}], hasNext: false}); await old;
  await pager.next({id: 'same'});
  assert.equal(pending.length, 2);
  pending[1].resolve({content: [{id: 'new'}], hasNext: false}); await current;
  assert.deepEqual(state.get('same').rows, [{id: 'new'}]);
});

test('hidden page cancels pending work without discarding loaded child pages', async () => {
  let state, signal;
  const request = deferred();
  const pager = createChildPages(async (_row, page, currentSignal) => {
    if (!page) return {content: [{id: 'retained'}], hasNext: true};
    signal = currentSignal; return request.promise;
  }, row => row.id, value => {state = value;});
  await pager.next({id: 'parent'});
  const loading = pager.next({id: 'parent'});
  pager.cancelPending();
  assert.equal(signal.aborted, true);
  assert.equal(state.get('parent').loading, false);
  assert.equal(state.get('parent').page, 1);
  request.resolve({content: [{id: 'stale'}], hasNext: false}); await loading;
  assert.deepEqual(state.get('parent').rows, [{id: 'retained'}]);
});
