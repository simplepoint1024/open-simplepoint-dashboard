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
const temporary = await mkdtemp(path.join(tmpdir(), 'simplepoint-inverse-authoring-'));
let server, browser, socket;
const errors = [];
let diagnostic = async () => ({errors});
const poll = async (read, description) => {
  const until = Date.now() + 30000;
  while (Date.now() < until) {const result = await read(); if (result) return result; await delay(30);}
  console.error(JSON.stringify(await diagnostic()));
  throw Error(`Timed out: ${description}`);
};
try {
  const rsbuild = await createRsbuild({cwd: root, rsbuildConfig: {plugins: [pluginReact()],
    source: {entry: {index: path.join(root, 'scripts/inverse-authoring-harness.tsx')}},
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
  diagnostic = async () => ({errors, page: await evaluate(`({text:document.body.textContent?.slice(0,3000),reads:window.inverseAuthoring?.reads()})`)});
  const fixture = expression => evaluate(`window.inverseAuthoring.${expression}`);
  const visible = selector => evaluate(`Array.from(document.querySelectorAll(${JSON.stringify(selector)})).filter(node => node.getClientRects().length).length`);
  const click = async (selector, text) => {
    await poll(() => evaluate(`Array.from(document.querySelectorAll(${JSON.stringify(selector)})).some(node => node.getClientRects().length && ${text ? `node.textContent.trim() === ${JSON.stringify(text)}` : 'true'})`), `visible ${selector} ${text ?? ''}`);
    await evaluate(`Array.from(document.querySelectorAll(${JSON.stringify(selector)})).find(node => node.getClientRects().length && ${text ? `node.textContent.trim() === ${JSON.stringify(text)}` : 'true'})?.click()`);
  };
  await poll(() => visible('.sp-form-properties-drawer [role=tab]'), 'form property tabs');
  await click('.sp-form-properties-drawer [role=tab]', '关联记录');
  await poll(() => evaluate(`document.body.textContent.includes('交易记录・日本語😀')`), 'localized inverse view list');
  await click('.sp-form-properties-drawer button', '编辑');
  await poll(async () => (await fixture('reads()')).length === 1, 'source relation metadata');
  assert.equal(await fixture('commits()'), 0);
  await fixture('finish(0, false)');
  await poll(() => visible('#inverse_settings_allowUnlink'), 'relation configuration');
  assert.equal(await evaluate(`document.querySelector('#inverse_settings_allowUnlink').disabled`), true, 'nonnullable field cannot enable unlink');
  assert.equal(await visible('.sp-form-properties-drawer .ant-drawer-close'), 0, 'nested editor locks parent drawer');
  await click('#inverse_settings_allowEdit');
  await click('.sp-form-inverse-modal .ant-modal-footer button', '应用修改');
  await poll(async () => (await fixture('commits()')) === 1, 'one document command');
  const changed = (await fixture('document()')).presentation.inverseViews[0];
  assert.equal(changed.allowEdit, true);
  assert.equal(changed.allowUnlink, false);
  assert.equal(changed.labelLocalization, undefined, 'editing flags preserves absent legacy label translations');
  assert.equal(changed.viewId, 'trades');
  await click('.sp-form-properties-drawer button', '编辑');
  await poll(async () => (await fixture('reads()')).length === 2, 'second metadata read');
  await fixture('finish(1)');
  await poll(() => evaluate(`!document.querySelector('#inverse_settings_allowUnlink')?.disabled`), 'nullable source unlocks action setting');
  await click('#inverse_settings_allowUnlink');
  await click('.sp-form-inverse-modal .ant-modal-footer button', '取消');
  await poll(() => visible('.sp-form-inverse-discard .ant-modal'), 'dirty cancellation confirmation');
  await click('.sp-form-inverse-discard .ant-btn-primary');
  await poll(async () => !(await visible('.sp-form-inverse-modal .ant-modal')), 'discard closes only editor');
  assert.equal(await fixture('commits()'), 1);
  assert.equal((await fixture('document()')).presentation.inverseViews[0].allowUnlink, false);
  await click('.sp-form-properties-drawer button', '编辑');
  await poll(async () => (await fixture('reads()')).length === 3, 'pending source read');
  await fixture('setSelected(false)');
  await poll(async () => (await fixture('reads()'))[2].aborted, 'retained page cancels metadata');
  await fixture('finish(2)');
  await poll(async () => !(await visible('.sp-form-inverse-modal .ant-modal')), 'suspended editor finishes closing');
  assert.equal(await fixture('commits()'), 1);
  await fixture('setSelected(true)');
  await poll(async () => (await fixture('reads()')).length >= 4, 'return reauthorizes source metadata');
  const last = (await fixture('reads()')).length - 1;
  await fixture('setGeneration(1)');
  await poll(async () => (await fixture('reads()'))[last].aborted, 'owner change cancels old source request');
  await fixture(`finish(${last})`);
  await poll(async () => !(await visible('.sp-form-inverse-modal .ant-modal')), 'owner change drops editor');
  assert.equal(await fixture('commits()'), 1);
  assert.deepEqual(errors, []);
  console.log(JSON.stringify({passed:true, scope:'isolated real browser designer; metadata directory is controlled', cases:[
    'authorized inverse configuration', 'nonnullable source disables unlink', 'nested editor locks parent',
    'single document command preserves identity and legacy localization absence', 'cancel restores document',
    'retained page cancels and reauthorizes metadata', 'owner change fences stale editor and metadata']}));
} finally {
  socket?.close();
  if (browser && browser.exitCode === null) {browser.kill('SIGTERM'); await new Promise(resolve => browser.once('exit', resolve));}
  await server?.server.close();
  await rm(temporary, {recursive: true, force: true});
}
