import assert from 'node:assert/strict';
import {mkdtemp, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';
import {setTimeout as delay} from 'node:timers/promises';
import {createRsbuild} from '@rsbuild/core';
import {pluginReact} from '@rsbuild/plugin-react';

const root = fileURLToPath(new URL('..', import.meta.url));
const temporary = await mkdtemp(path.join(tmpdir(), 'simplepoint-live-reference-'));
let server, browser, socket;
const errors = [];
const poll = async (read, description) => {
  const until = Date.now() + 30000;
  while (Date.now() < until) {const result = await read(); if (result) return result; await delay(30);}
  throw Error(`Timed out: ${description}`);
};
try {
  const rsbuild = await createRsbuild({cwd: root, rsbuildConfig: {plugins: [pluginReact()],
    source: {entry: {index: path.join(root, 'scripts/live-reference-harness.tsx')}},
    output: {distPath: {root: path.join(temporary, 'dist')}},
    dev: {hmr: false, liveReload: false}, server: {host: '127.0.0.1', port: 0, open: false}}});
  server = await rsbuild.startDevServer();
  browser = spawn(process.env.CHROME_BIN ?? '/usr/bin/google-chrome-stable', [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-extensions', '--disable-background-networking',
    '--no-first-run', `--user-data-dir=${temporary}/profile`, '--remote-debugging-port=0', 'about:blank',
  ], {stdio: 'ignore'});
  const port = await poll(async () => {
    try {return (await readFile(`${temporary}/profile/DevToolsActivePort`, 'utf8')).split('\n')[0];} catch {return undefined;}
  }, 'browser startup');
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl);
  await new Promise(resolve => socket.addEventListener('open', resolve, {once: true}));
  let sequence = 0;
  const pending = new Map();
  socket.addEventListener('message', ({data}) => {
    const message = JSON.parse(data);
    if (message.id) {
      const waiter = pending.get(message.id); pending.delete(message.id);
      if (message.error) waiter.reject(Error(JSON.stringify(message.error))); else waiter.resolve(message.result);
    } else if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails);
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence; pending.set(id, {resolve, reject}); socket.send(JSON.stringify({id, method, params}));
  });
  const evaluate = async expression => {
    const result = await send('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true});
    if (result.exceptionDetails) throw Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  await send('Runtime.enable');
  await send('Page.navigate', {url: `http://127.0.0.1:${server.server.httpServer.address().port}/`});
  const fixture = expression => evaluate(`window.liveFixture.${expression}`);
  const state = () => evaluate(`window.liveResult`);
  const count = async () => (await fixture('calls()')).length;
  await poll(() => evaluate(`Boolean(window.liveFixture)`), 'fixture startup');
  await poll(async () => await count() === 2, 'grouped reads');
  assert.deepEqual((await fixture('calls()')).map(call => call.ids.length).sort((a,b)=>a-b), [1, 40]);
  assert.equal((await state()).value.amount, null);
  await fixture(`finish(0,'9007199254740993.12')`); await fixture(`finish(1,'2.50')`);
  await poll(async () => !(await state()).loading, 'initial values');
  assert.equal((await state()).value.amount, '9007199254740993.12');
  assert.equal((await state()).value.lines[0].values.amount, '2.50');
  assert.equal((await fixture('input()')).amount, 'MUST-NOT-KEEP');
  await fixture('reverse()'); await delay(250);
  assert.equal(await count(), 2);
  assert.equal((await state()).value.lines[39].id, 'aaaaaaaa-1111-2222-3333-000000000100');
  await evaluate('window.liveResult.refresh()');
  await poll(async () => await count() === 4, 'refresh');
  await fixture('setReference(9)');
  await poll(async () => (await fixture('calls()'))[2].aborted, 'selection cancellation');
  await fixture(`finish(2,'99.00')`); await fixture(`finish(3,'99.00')`);
  assert.equal((await state()).value.amount, null);
  await poll(async () => await count() === 6, 'new selection');
  await fixture('set({selected:false})');
  await poll(async () => (await fixture('calls()'))[4].aborted, 'hidden page cancellation');
  await fixture(`finish(4,'88.00')`); await fixture(`finish(5,'88.00')`);
  assert.equal((await state()).value.amount, null);
  await fixture('set({selected:true})');
  await poll(async () => await count() === 8, 'reactivation');
  await evaluate(`localStorage.setItem('sp.tenantId','another-tenant'); window.dispatchEvent(new Event('sp-set-tenant'));`);
  await poll(async () => (await fixture('calls()'))[6].aborted, 'workspace cancellation');
  await fixture(`finish(6,'77.00')`); await fixture(`finish(7,'77.00')`);
  await poll(async () => await count() === 10, 'new scope');
  await fixture(`finish(8,'3.00',true)`); await fixture(`finish(9,'3.00')`);
  await poll(async () => (await state()).failed, 'malformed identities');
  assert.equal((await state()).value.amount, null);
  await evaluate('window.liveResult.refresh()');
  await poll(async () => await count() === 12, 'retry');
  await fixture('set({frozen:true})');
  await poll(async () => (await fixture('calls()'))[10].aborted, 'snapshot isolation');
  await fixture(`finish(10,'66.00')`); await fixture(`finish(11,'66.00')`); await delay(300);
  assert.equal(await count(), 12); assert.equal((await state()).value.amount, null);
  await fixture('setReference(null)'); await fixture('set({frozen:false,owner:1})');
  await poll(async () => await count() === 13, 'cleared root');
  assert.equal((await fixture('calls()'))[12].relation, 'product');
  await fixture(`finish(12,'4.00')`);
  await poll(async () => !(await state()).loading, 'detail completion');
  assert.equal((await state()).value.amount, null);
  assert.equal((await state()).value.lines[0].values.amount, '4.00');
  await fixture('oversize()');
  await poll(async () => (await state()).failed, 'invalid projection budget');
  assert.deepEqual((await state()).value.lines, []);
  assert.equal((await state()).value.amount, null);
  assert.equal((await fixture('input()')).lines.length, 41);
  assert.deepEqual(errors, []);
  console.log(JSON.stringify({passed: true, scope: 'isolated browser hooks, not deployed business acceptance',
    cases: ['40-row batching','canonical precision','single edit buffer','reorder','refresh','selection and late response fence',
      'retained page cancellation','workspace authorization fence','malformed response','snapshot isolation','clear selection','invalid plan clears stale outputs']}));

} finally {
  socket?.close();
  if (browser && browser.exitCode === null) {browser.kill('SIGTERM'); await new Promise(resolve => browser.once('exit', resolve));}
  await server?.server.close();
  await rm(temporary, {recursive: true, force: true});
}
