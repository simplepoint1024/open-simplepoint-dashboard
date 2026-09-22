import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import test from 'node:test';
import {createRequestSignal} from './requestSignal.ts';

for (const timeout of [0, 60000]) {
  test(`abort still closes streaming body after headers and cleanup (timeout ${timeout})`, async () => {
    const server = createServer((req, res) => {
      res.writeHead(200, {'Content-Type': 'text/event-stream'});
      res.write(': connected\n\n');
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const controller = new AbortController();
    const request = createRequestSignal(controller.signal, timeout);
    try {
      const response = await fetch(`http://127.0.0.1:${server.address().port}`, {signal: request.signal});
      request.cleanup(); // sendRequest returns Response after headers.
      const reader = response.body.getReader();
      await reader.read();
      const pending = reader.read();
      controller.abort();
      await assert.rejects(pending, {name: 'AbortError'});
      assert.equal(request.isTimeout(), false);
    } finally {
      request.cleanup();
      controller.abort();
      server.closeAllConnections();
      await new Promise(resolve => server.close(resolve));
    }
  });
}

test('timeouts abort only the request, not the caller; cleanup clears timeout', t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const controller = new AbortController();
  const request = createRequestSignal(controller.signal, 10);
  t.mock.timers.tick(10);
  assert.equal(request.isTimeout(), true);
  assert.equal(request.signal.reason.name, 'TimeoutError');
  assert.equal(controller.signal.aborted, false);
  const completed = createRequestSignal(undefined, 10);
  completed.cleanup();
  t.mock.timers.tick(10);
  assert.equal(completed.signal.aborted, false);
});

test('an already aborted caller stays aborted and is never reclassified as timeout', t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const controller = new AbortController();
  controller.abort('navigation');
  const request = createRequestSignal(controller.signal, 10);
  t.mock.timers.tick(10);
  assert.equal(request.signal.reason, 'navigation');
  assert.equal(request.isTimeout(), false);
});
