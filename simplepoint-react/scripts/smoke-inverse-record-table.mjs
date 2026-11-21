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
const temporary = await mkdtemp(path.join(tmpdir(), 'simplepoint-inverse-table-'));
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
    source: {entry: {index: path.join(root, 'scripts/inverse-record-table-harness.tsx')}},
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
  diagnostic = async () => ({errors, page: await evaluate(`({text:document.body.textContent?.slice(0,2500),calls:window.inverseTable?.calls()})`)});
  const fixture = expression => evaluate(`window.inverseTable.${expression}`);
  const click = label => evaluate(`Array.from(document.querySelectorAll('#fixture button')).find(button => button.textContent.trim() === ${JSON.stringify(label)})?.click()`);
  await poll(() => evaluate(`document.getElementById('fixture')?.textContent.includes('交易 日本')`), 'related record list');
  const requests = await fixture('calls()');
  for (const suffix of ['/records/schema', '/records/query']) {
    const request = requests.find(item => item.url.includes(suffix));
    assert(request, suffix);
    const url = new URL(request.url, 'http://fixture');
    assert.equal(url.searchParams.get('parentFormId'), 'customer-form');
    assert.equal(url.searchParams.get('parentFormVersion'), '7');
    assert(url.pathname.includes('/records/parent-a/relations/trades/'));
  }
  await click('Add');
  await poll(() => evaluate(`!!document.querySelector('.ant-drawer-body input[value="parent-a"]')`), 'related create parent default');
  await evaluate(`document.querySelector('.ant-drawer-close')?.click()`);
  await poll(() => evaluate(`document.querySelectorAll('.ant-drawer-open').length === 0`), 'clean create drawer close');

  await fixture('hold(true)');
  await evaluate(`document.querySelector('.ant-table-tbody input[type=checkbox]')?.click()`);
  await poll(() => evaluate(`Array.from(document.querySelectorAll('#fixture button')).some(button => button.textContent.trim() === 'View' && !button.disabled)`), 'selected record view action');
  await click('View');
  await poll(async () => (await fixture('held()')).length === 1, 'held detail read');
  await fixture(`setParent('parent-b')`);
  await poll(async () => (await fixture('held()'))[0].aborted, 'parent change cancels old detail read');
  await fixture('finish(0)');
  await poll(async () => (await fixture('calls()')).some(item => item.url.includes('/parent-b/') && item.url.includes('/query')), 'new parent list');
  assert.equal(await evaluate(`document.querySelectorAll('.app-record-detail .ant-drawer-open').length`), 0);

  await evaluate(`document.querySelector('.ant-table-tbody input[type=checkbox]')?.click()`);
  await poll(() => evaluate(`Array.from(document.querySelectorAll('#fixture button')).some(button => button.textContent.trim() === 'Edit' && !button.disabled)`), 'selected record edit action');
  await click('Edit');
  await poll(async () => (await fixture('held()')).length === 2, 'standard edit loader');
  await fixture('setSelected(false)');
  await poll(async () => (await fixture('held()'))[1].aborted, 'retained page cancels edit read');
  await fixture('finish(1)');
  await fixture('setSelected(true)');
  await fixture('hold(false)');
  await poll(() => evaluate(`document.getElementById('fixture')?.textContent.includes('交易 日本')`), 'retained list resumes');
  const recordReads = (await fixture('calls()')).filter(item => /records\/aaaaaaaa/.test(item.url));
  assert(recordReads.length >= 2);
  assert(recordReads.every(item => new URL(item.url, 'http://fixture').searchParams.get('parentFormVersion') === '7'));
  await fixture(`setMode('related')`);
  await poll(() => evaluate(`document.querySelector('.ant-tabs-tab-btn')?.textContent.includes('关联交易') && document.getElementById('fixture')?.textContent.includes('交易 日本')`), 'authorized inverse catalog and shared source runtime');
  await click('Add');
  await poll(() => evaluate(`document.querySelector('.ant-drawer-body')?.textContent.includes('aaaaaaaa-1111-2222-3333-000000000001') || !!document.querySelector('.ant-drawer-body input[value="aaaaaaaa-1111-2222-3333-000000000001"]')`), 'server-described parent field default');
  assert.equal(await evaluate(`Array.from(document.querySelectorAll('.ant-drawer-body input')).some(input => input.value === 'aaaaaaaa-1111-2222-3333-000000000001' && !input.disabled && !input.readOnly)`), false, 'root parent cannot be edited through the inverse form');
  assert.equal(await fixture('locked()'), true);
  assert.equal(await evaluate(`document.querySelectorAll('.ant-tabs-tab-disabled').length`), 1);
  await evaluate(`document.querySelector('.ant-drawer-close')?.click()`);
  await poll(async () => !(await fixture('locked()')), 'nested editor releases owner lock');
  await evaluate(`Array.from(document.querySelectorAll('.ant-tabs-tab-btn')).find(item => item.textContent.includes('关联订单'))?.click()`);
  await poll(async () => (await fixture('calls()')).some(item => item.url.includes('/relations/orders/records/query')), 'second authorized view uses its scoped controller');
  await poll(() => evaluate(`document.getElementById('fixture')?.textContent.includes('交易 日本')`), 'second view list rendered');
  await evaluate(`document.querySelector('.ant-table-tbody input[type=checkbox]')?.click()`);
  await poll(() => evaluate(`Array.from(document.querySelectorAll('#fixture button')).some(button => button.textContent.trim() === 'Unlink' && !button.disabled)`), 'server-authorized unlink action');
  await click('Unlink');
  await poll(() => evaluate(`!!document.querySelector('.app-record-unlink.ant-drawer-open')`), 'unlink confirmation drawer');
  assert.equal(await fixture('locked()'), true);
  await fixture('failUnlink(true)');
  await evaluate(`Array.from(document.querySelectorAll('.app-record-unlink button')).find(button => button.textContent.trim() === '解除关联')?.click()`);
  await poll(() => evaluate(`document.querySelector('.app-record-unlink')?.textContent.includes('重试')`), 'uncertain unlink keeps retry intent');
  assert.equal(await evaluate(`document.querySelectorAll('.app-record-unlink .ant-drawer-close').length`), 0);
  await fixture('failUnlink(false)');
  await evaluate(`Array.from(document.querySelectorAll('.app-record-unlink button')).find(button => button.textContent.trim() === '重试')?.click()`);
  await poll(async () => !(await fixture('locked()')), 'successful unlink releases parent');
  const unlinkRequests = (await fixture('calls()')).filter(item => item.url.includes('/unlink'));
  assert.equal(unlinkRequests.length, 2);
  assert.deepEqual(unlinkRequests[0], unlinkRequests[1], 'uncertain unlink repeats the exact URL, operation ID and body');
  assert.deepEqual(unlinkRequests[0].body.values, {customer: null});
  assert.equal(unlinkRequests[0].body.runtime.formVersion, 3);
  assert.equal(new URL(unlinkRequests[0].url, 'http://fixture').searchParams.get('parentFormVersion'), '7');
  await fixture('setOwned()');
  await poll(() => evaluate(`document.querySelector('.ant-tabs-tab-btn')?.textContent.includes('关联交易') && document.getElementById('fixture')?.textContent.includes('交易 日本')`), 'owned inverse runtime');
  await evaluate(`document.querySelector('#fixture .ant-table-tbody input[type=checkbox]')?.click()`);
  await poll(() => evaluate(`Array.from(document.querySelectorAll('#fixture button')).some(button => button.textContent.trim() === 'Unlink' && !button.disabled)`), 'owned unlink enabled');
  await click('Unlink');
  await poll(() => evaluate(`document.querySelector('.app-record-unlink')?.textContent.includes('解除这一行')`), 'owned detail read-only preview');
  assert.equal(await evaluate(`document.querySelectorAll('.app-record-unlink input[type=radio]:checked').length`), 1);
  await evaluate(`Array.from(document.querySelectorAll('.app-record-unlink button')).find(button => button.textContent.trim() === '解除关联')?.click()`);
  await poll(async () => !(await fixture('locked()')), 'owned unlink completed');
  const ownedUnlink = (await fixture('calls()')).filter(item => item.url.includes('/unlink')).at(-1);
  assert.equal(new URL(ownedUnlink.url, 'http://fixture').searchParams.get('sourceRowId'), 'bbbbbbbb-1111-2222-3333-000000000002');
  assert.deepEqual(ownedUnlink.body.values, {lines: [
    {id: 'bbbbbbbb-1111-2222-3333-000000000001', version: 5, values: {}},
    {id: 'bbbbbbbb-1111-2222-3333-000000000002', version: 7, values: {customer: null}},
  ]});
  await fixture('enableSubmission()');
  await poll(() => evaluate(`document.getElementById('fixture')?.textContent.includes('交易 日本') && document.getElementById('fixture')?.textContent.includes('Submit')`), 'related submission schema');
  await evaluate(`document.querySelector('#fixture .ant-table-tbody input[type=checkbox]')?.click()`);
  await poll(() => evaluate(`Array.from(document.querySelectorAll('#fixture button')).some(button => button.textContent.trim() === 'Submit' && !button.disabled)`), 'submission action available');
  await click('Submit');
  await poll(() => evaluate(`document.querySelector('.app-workflow-submission')?.textContent.includes('交易 日本')`), 'read-only related submission confirmation');
  assert.equal(await evaluate(`Array.from(document.querySelectorAll('.app-workflow-submission input')).some(input => input.value === '交易 日本' && !input.disabled && !input.readOnly)`), false);
  await evaluate(`Array.from(document.querySelectorAll('.app-workflow-submission button')).find(button => button.textContent.trim() === '提交审批')?.click()`);
  await poll(() => evaluate(`document.querySelector('.app-workflow-submission')?.textContent.includes('审批已发起')`), 'accepted related submission status');
  const submissionRequests = (await fixture('calls()')).filter(item => item.url.includes('/submissions'));
  for (const item of submissionRequests) {
    const url = new URL(item.url, 'http://fixture');
    assert.equal(url.searchParams.get('parentFormId'), 'customer-form');
    assert.equal(url.searchParams.get('parentFormVersion'), '7');
    assert(url.pathname.includes('/relations/trades/submissions'));
  }
  const submitted = submissionRequests.find(item => item.method === 'POST');
  assert(submitted);
  assert.deepEqual(submitted.body.values, {}, 'submission confirms saved values without sending editable business input');
  assert.equal(submitted.body.runtime.formVersion, 3);
  assert.equal(submitted.body.expectedVersion, 2);
  await evaluate(`Array.from(document.querySelectorAll('.app-workflow-submission button')).find(button => button.textContent.trim() === '完成')?.click()`);
  await poll(async () => !(await fixture('locked()')), 'submission close releases owner');
  await fixture('denyCatalog(true)');
  await poll(() => evaluate(`!document.getElementById('fixture')?.textContent.includes('关联订单') && !document.getElementById('fixture')?.textContent.includes('交易 日本')`), 'revoked views are removed with their records');
  await fixture('target()');
  await poll(() => evaluate(`document.getElementById('fixture')?.textContent.includes('交易 日本') && Array.from(document.querySelectorAll('#fixture button')).some(button => button.textContent.trim() === 'Edit' && !button.disabled)`), 'target detail uses server record actions');
  assert.equal(await evaluate(`document.querySelectorAll('#fixture .ant-table').length`), 0, 'detail does not render a second list');
  assert.equal(await evaluate(`Array.from(document.querySelectorAll('#fixture button')).some(button => ['Add','View','Unlink'].includes(button.textContent.trim()))`), false);
  await click('Edit');
  await poll(() => evaluate(`!!document.querySelector('.sp-simple-table-drawer input[value="交易 日本"]')`), 'target reuses standard editing drawer');
  assert.equal(await fixture('locked()'), true, 'target editor locks the owning inspector');
  await evaluate(`(() => {const input=document.querySelector('.sp-simple-table-drawer input[value="交易 日本"]'); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,'目标更新 日本'); input.dispatchEvent(new Event('input',{bubbles:true}));})()`);
  await evaluate(`Array.from(document.querySelectorAll('.sp-simple-table-drawer button')).find(button => button.textContent.trim() === '保存草稿')?.click()`);
  await poll(async () => (await fixture('calls()')).some(item => item.method === 'PUT' && item.body?.values?.name === '目标更新 日本'), 'target saves through standard record facade');
  await poll(() => evaluate(`document.getElementById('fixture')?.textContent.includes('目标更新 日本') && !document.querySelector('.sp-simple-table-drawer.ant-drawer-open')`), 'target refreshes after save');
  assert.equal(await fixture('locked()'), false);
  await fixture('denyEdits()');
  await poll(() => evaluate(`!Array.from(document.querySelectorAll('#fixture button')).some(button => button.textContent.trim() === 'Edit')`), 'target permission refresh removes edit');
  assert.equal(await evaluate('window.operationProbe.begin()'), true);
  await fixture('setSelected(false)');
  await delay(80);
  assert.equal(await evaluate('window.operationProbe.aborted()'), false, 'started writes survive retained-page hiding');
  await evaluate(`localStorage.setItem('sp.tenantId','another-fixture'); window.dispatchEvent(new Event('sp-set-tenant'));`);
  await poll(() => evaluate('window.operationProbe.aborted()'), 'authorization change still cancels the previous operation');
  await evaluate('window.operationProbe.finish()');
  assert.deepEqual(errors, []);
  console.log(JSON.stringify({passed: true, scope: 'isolated shared table in a real browser; no live business writes',
    cases: ['scoped schema and pagination', 'create parent defaults', 'same standard editor',
      'owner change cancels and fences details', 'retained page cancels edit reads',
      'started writes survive hidden page', 'authorization change cancels previous operation',
      'authorized inverse catalog and source runtime', 'nested editor locks parent navigation',
      'view switching rebinds standard controller', 'revoked views clear related records',
      'server-authorized unlink confirmation', 'uncertain unlink preserves exact intent and owner lock', 'owned unlink preserves full aggregate and row versions', 'related submission is read-only and scopes preparation, write and status',
      'target detail shares server actions and standard edit/save lifecycle', 'target editor locks its inspector', 'target edit permission refresh']}));
} finally {
  socket?.close();
  if (browser && browser.exitCode === null) {browser.kill('SIGTERM'); await new Promise(resolve => browser.once('exit', resolve));}
  await server?.server.close();
  await rm(temporary, {recursive: true, force: true});
}
