import assert from 'node:assert/strict';
import {test} from 'node:test';
import {loadMessageBundle} from './messageLoader.ts';

test('transient failures recover the complete bundle with bounded backoff', async () => {
  const network = new Error('network');
  const pauses = [];
  let calls = 0;
  const result = await loadMessageBundle(async () => {
    if (++calls < 3) throw network;
    return {'form.title': '报销・申請', 'form.empty': ''};
  }, error => error === network, async ms => { pauses.push(ms); });
  assert.deepEqual(result, {'form.title': '报销・申請', 'form.empty': ''});
  assert.equal(calls, 3);
  assert.deepEqual(pauses, [250, 750]);
});

test('exhausted transport errors reject instead of succeeding with an empty dictionary', async () => {
  const network = new Error('network');
  let calls = 0;
  await assert.rejects(loadMessageBundle(async () => {
    calls += 1;
    throw network;
  }, () => true, async () => {}), error => error === network);
  assert.equal(calls, 3);
});

test('authorization, abort and validation failures do not trigger retry', async () => {
  for (const error of [new Error('forbidden'), new Error('unauthorized'), new Error('aborted')]) {
    let calls = 0;
    await assert.rejects(loadMessageBundle(async () => {
      calls += 1;
      throw error;
    }, () => false, async () => assert.fail('must not retry')), reason => reason === error);
    assert.equal(calls, 1);
  }
});

test('only successful dictionary responses may enter the translation cache', async () => {
  for (const data of [null, undefined, [], 'login page', {label: 1}, {label: {nested: 'value'}}]) {
    await assert.rejects(loadMessageBundle(async () => data, () => false), /Invalid translation bundle/);
  }
  assert.deepEqual(await loadMessageBundle(async () => ({}), () => false), {});
});
