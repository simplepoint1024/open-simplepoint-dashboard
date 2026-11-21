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
const temporary = await mkdtemp(path.join(tmpdir(), 'simplepoint-reference-navigation-'));
let server, browser, socket, inspect;
const errors = [];
const poll = async (read, description) => {
  const until = Date.now() + 30000;
  while (Date.now() < until) {const result = await read(); if (result) return result; await delay(30);}
  console.error(JSON.stringify({errors: errors.map(error => error.exception?.description ?? error.text), page: inspect ? await inspect() : null}));
  throw Error(`Timed out: ${description}`);
};
try {
  const rsbuild = await createRsbuild({cwd: root, rsbuildConfig: {plugins: [pluginReact()],
    source: {entry: {index: path.join(root, 'scripts/reference-navigation-harness.tsx')}},
    resolve: {alias: {'@': path.join(root, 'modules/simplepoint-host/src')}},
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
  const fixture = expression => evaluate(`window.referenceNavigation.${expression}`);
  inspect = () => evaluate(`({location:window.location.hash,text:document.body.innerText, requests:window.referenceNavigation?.requests(), unexpected:window.referenceNavigation?.unexpected()})`);
  const route = '/app/runtime/sales/trades';
  const active = () => evaluate(`document.querySelector('.nb-tab-panel-active')?.innerText ?? ''`);
  const tabs = () => evaluate(`document.querySelectorAll('.nb-tabs-bar [role="tab"]').length`);
  const requests = () => fixture('requests()');
  const reload = async () => {
    const boot = await fixture('boot');
    await send('Page.reload');
    await poll(() => evaluate(`!!window.referenceNavigation && window.referenceNavigation.boot !== ${JSON.stringify(boot)}`), 'new document initialized');
  };
  const navigate = location => fixture(`navigate(${JSON.stringify(location)})`);
  const closeActive = () => evaluate(`document.querySelector('.nb-tabs-bar .ant-tabs-tab-active .ant-tabs-tab-remove').click()`);
  await poll(() => evaluate(`!!window.referenceNavigation`), 'host startup');
  await poll(async () => (await active()).includes('HOME'), 'initial host redirect settled');
  await navigate(`${route}?filter=active`);
  await poll(async () => (await active()).includes('EMPTY-DRAFT'), 'ordinary business page');
  await fixture(`draft('UNSAVED-TRADE')`);
  await poll(async () => (await active()).includes('UNSAVED-TRADE'), 'dirty source');
  await navigate(`${route}?sp-view=broken`);
  await poll(async () => (await active()).includes('应用页面地址无效'), 'invalid bookmark has its own error view');
  assert.equal((await requests()).length, 0, 'malformed view never queries a target');
  await closeActive();
  await poll(async () => (await active()).includes('UNSAVED-TRADE'), 'invalid view cannot overwrite draft');
  const first = await fixture('link(1)'), second = await fixture('link(2)');
  await navigate(first);
  await poll(async () => (await requests()).length === 1, 'reference page reads exact relation');
  assert.equal(await tabs(), 3, 'home, source, reference are separate tabs');
  assert((await requests())[0].url.includes('formId=trade') && (await requests())[0].url.includes('formVersion=3'));
  await fixture(`finish(0,'FIRST-CUSTOMER')`);
  await poll(async () => (await active()).includes('FIRST-CUSTOMER'), 'live readonly target');
  assert.equal(await evaluate(`document.querySelectorAll('.nb-tab-panel-active input,.nb-tab-panel-active [role="combobox"]').length`), 0);
  await evaluate(`window.dispatchEvent(new CustomEvent('sp-refresh-route',{detail:{path:${JSON.stringify(first)}}}))`);
  await poll(async () => (await requests()).length === 2, 'refresh rereads selected detail');
  await fixture(`finish(1,'REFRESHED-CUSTOMER')`);
  await poll(async () => (await active()).includes('REFRESHED-CUSTOMER'), 'refreshed detail');
  assert.equal(await evaluate(`document.querySelector('[data-draft]').textContent`), 'UNSAVED-TRADE');
  assert.equal(await evaluate(`document.querySelector('[data-draft]').parentElement.dataset.mount`), '1', 'detail refresh must not remount source');
  await navigate(second);
  await poll(async () => (await requests()).length === 3, 'second independent detail');
  await fixture(`finish(2,'SECOND-CUSTOMER')`);
  await poll(async () => (await active()).includes('SECOND-CUSTOMER'), 'second detail visible');
  assert.equal(await tabs(), 4);
  await closeActive();
  await poll(async () => (await requests()).length === 4, 'closing second detail reauthorizes first');
  assert(!(await active()).includes('REFRESHED-CUSTOMER'), 'cached data stays hidden during reauthorization');
  await fixture(`finish(3,'RETURNED-CUSTOMER')`);
  await poll(async () => (await active()).includes('RETURNED-CUSTOMER'), 'returned first detail');
  await closeActive();
  await poll(async () => (await active()).includes('UNSAVED-TRADE'), 'closing first detail restores original draft');
  assert.equal(await evaluate(`window.location.hash`), `#${route}?filter=active`, 'source query is retained');
  assert.equal(await tabs(), 2);
  await closeActive();
  await poll(() => evaluate(`!!document.querySelector('.ant-modal-confirm')`), 'original dirty owner still guards closing');
  await evaluate(`document.querySelector('.ant-modal-confirm .ant-btn-default').click()`);
  await poll(() => evaluate(`!document.querySelector('.ant-modal-confirm')`), 'cancel discard');
  assert((await active()).includes('UNSAVED-TRADE'));
  await navigate(first);
  await poll(async () => (await requests()).length === 5, 'reopened target request');
  await navigate(route);
  await poll(async () => (await requests())[4].aborted, 'background detail read cancelled');
  await fixture(`finish(4,'LATE-SECRET')`);
  assert(!(await active()).includes('LATE-SECRET'));
  await navigate(first);
  await poll(async () => (await requests()).length === 6, 'reactivated target requests fresh permission');
  await fixture(`finish(5,'',403)`);
  await poll(async () => (await active()).includes('应用页面加载失败'), 'revoked target fails visibly');
  assert(!(await active()).includes('LATE-SECRET'));
  await evaluate(`document.querySelector('.nb-tab-panel-active button').click()`);
  await poll(async () => (await requests()).length === 7, 'retry through shared action');
  await fixture(`finish(6,'REAUTHORIZED')`);
  await poll(async () => (await active()).includes('REAUTHORIZED'), 'retry success');
  await fixture(`draft('')`);
  await delay(350);
  await evaluate(`localStorage.removeItem('sp.nav.tabs')`);
  await reload();
  await poll(() => evaluate(`!!window.referenceNavigation && document.querySelectorAll('.nb-tabs-bar [role="tab"]').length === 2`), 'bookmark opens without stored tabs');
  await poll(async () => (await requests()).length === 1, 'restored bookmark independently reads target');
  await fixture(`finish(0,'BOOKMARK-CUSTOMER')`);
  await poll(async () => (await active()).includes('BOOKMARK-CUSTOMER'), 'bookmark target');
  assert.equal(await evaluate('window.location.hash'), `#${first}`);
  await delay(350);
  await reload();
  await poll(async () => await evaluate(`!!window.referenceNavigation`) && (await requests()).length === 1, 'persisted tab restoration reauthorizes');
  await fixture(`finish(0,'RESTORED-CUSTOMER')`);
  await poll(async () => (await active()).includes('RESTORED-CUSTOMER'), 'restored target');
  assert.equal(await tabs(), 2, 'restoration does not duplicate canonical identity');
  await evaluate(`localStorage.setItem('sp.tenantId','other'); window.dispatchEvent(new CustomEvent('sp-set-tenant',{detail:'other'}))`);
  await poll(async () => await tabs() === 1 && (await active()).includes('HOME'), 'workspace change discards retained detail');
  assert(!await evaluate(`document.body.textContent.includes('RESTORED-CUSTOMER')`));
  assert.deepEqual(await fixture('unexpected()'), []);
  assert.deepEqual(errors, []);
  console.log(JSON.stringify({status:'passed', scenarios:13, scope:'actual host navigation, retained views, readonly reference HTTP fixture; no production backend'}));
} finally {
  socket?.close();
  if (browser && browser.exitCode === null) {browser.kill('SIGTERM'); await new Promise(resolve => browser.once('exit', resolve));}
  await server?.server.close();
  await rm(temporary, {recursive: true, force: true});
}
