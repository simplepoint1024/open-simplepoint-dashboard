import assert from 'node:assert/strict';
import test from 'node:test';
import {addCsrfHeader, getCsrfToken} from './csrf.ts';

test('tokens are shared only in flight and never sent to other origins', async () => {
  const originalFetch = globalThis.fetch;
  const originalWindow = globalThis.window;
  globalThis.window = {location: {origin: 'https://app.test', href: 'https://app.test/'}};
  let count = 0;
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/csrf'); assert.equal(options.redirect, 'error'); assert.equal(options.cache, 'no-store');
    count++;
    return Response.json({token: `masked-${count}`, headerName: 'X-CSRF-TOKEN', parameterName: '_csrf'});
  };
  try {
    const headers = new Headers();
    await Promise.all([addCsrfHeader('/common/items', 'POST', headers), getCsrfToken()]);
    assert.equal(count, 1); assert.equal(headers.get('X-CSRF-TOKEN'), 'masked-1');
    const renewed = await getCsrfToken(); assert.equal(renewed.token, 'masked-2');
    for (const [url, method] of [['https://other.test/upload', 'POST'], ['/common/items', 'GET']]) {
      const ignored = new Headers(); await addCsrfHeader(url, method, ignored);
      assert.equal(ignored.has('X-CSRF-TOKEN'), false);
    }
    assert.equal(count, 2);
  } finally { globalThis.fetch = originalFetch; globalThis.window = originalWindow; }
});

test('unavailable or malformed CSRF response fails closed and is not cached', async () => {
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async () => new Response('', {status: 503});
    await assert.rejects(getCsrfToken());
    globalThis.fetch = async () => Response.json({token: 'bad', headerName: 'Authorization', parameterName: '_csrf'});
    await assert.rejects(getCsrfToken());
    globalThis.fetch = async () => Response.json({token: 'fresh', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf'});
    assert.equal((await getCsrfToken()).token, 'fresh');
  } finally { globalThis.fetch = original; }
});
