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
const temporary = await mkdtemp(path.join(tmpdir(), 'simplepoint-reference-mapping-'));
let server, browser, socket;
const errors = [];
const poll = async (read, description) => {
  const until = Date.now() + 30000;
  while (Date.now() < until) {const result = await read(); if (result) return result; await delay(30);}
  throw Error(`Timed out: ${description}`);
};
try {
  const rsbuild = await createRsbuild({cwd: root, rsbuildConfig: {plugins: [pluginReact()],
    source: {entry: {index: path.join(root, 'scripts/reference-mapping-harness.tsx')}},
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
  const fixture = expression => evaluate(`window.mappingFixture.${expression}`);
  // Assert the action is actually exposed above every overlay, then dispatch in the same frame.
  // This avoids clicking coordinates from a previous frame of a modal/dropdown animation.
  const textClick = (selector, text) => poll(() => evaluate(`(() => {
    const e = [...document.querySelectorAll(${JSON.stringify(selector)})].find(e => e.textContent.trim() === ${JSON.stringify(text)} && e.getClientRects().length);
    if (!e || e.disabled) return false; e.scrollIntoView({block:'center'});
    const r=e.getBoundingClientRect(), hit=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);
    if (!e.contains(hit)) return false; e.click(); return true;
  })()`), `visible clickable action: ${text}`);
  const refresh = (selector = '#form_main_customer') => evaluate(`document.querySelector(${JSON.stringify(selector)}).closest('.ant-form-item-control').querySelector('button[aria-label="重新带入关联值"]')?.click()`);
  const modal = '.sp-form-runtime-mapping:not([style*="display: none"]) .ant-modal';
  await poll(() => evaluate(`Boolean(window.mappingFixture) && Boolean(document.querySelector('#form_main_customer'))`), 'form startup');
  await refresh();
  await poll(async () => (await fixture('calls()')).length === 1, 'observation');
  assert.equal(await fixture('submit()'), false, 'SForm preparation blocks save');
  await fixture('finish(0)');
  await poll(() => evaluate(`!!document.querySelector('${modal}')?.getClientRects().length`), 'confirmation');
  assert.equal((await fixture('input()')).amount, '12.00');
  await textClick(modal + ' button', '确认带入');
  await poll(() => evaluate(`!document.querySelector('${modal}')?.getClientRects().length`), 'confirmation dismissed');
  await textClick('button', '保存');
  await poll(async () => (await fixture('writes()')).length === 1, 'save');
  let saved = (await fixture('writes()'))[0];
  assert.equal(saved.values.amount, '12.00');
  assert.deepEqual(saved.mappings.confirmations[0].applyDefaultMappingIds, []);
  assert.equal(saved.values.name, '原客户', 'transport strips snapshots at actual owner; generic buffer keeps existing display');
  await refresh(); await poll(async () => (await fixture('calls()')).length === 2, 'refresh');
  await fixture('finish(1)');
  await poll(() => evaluate(`!!document.querySelector('${modal}')?.getClientRects().length`), 'refreshed confirmation');
  await evaluate(`document.querySelector('${modal} .ant-table-tbody input[type=checkbox]:not(:disabled)').click()`);
  await textClick(modal + ' button', '确认带入');
  await poll(async () => (await fixture('input()')).amount === '9007199254740993.12', 'selected default');
  await textClick('button', '保存');
  await poll(async () => (await fixture('writes()')).length === 2, 'default save');
  saved = (await fixture('writes()'))[1];
  assert.equal(saved.values.amount, '9007199254740993.12');
  assert.deepEqual(saved.mappings.confirmations[0].applyDefaultMappingIds, ['default']);
  await refresh(); await poll(async () => (await fixture('calls()')).length === 3, 'cancel observation');
  await fixture('finish(2,"4.00")');
  await poll(() => evaluate(`!!document.querySelector('${modal}')?.getClientRects().length`), 'cancel dialog');
  await textClick(modal + ' button', '取消');
  assert.equal((await fixture('input()')).amount, '9007199254740993.12');
  await poll(() => evaluate(`!document.querySelector('${modal}')?.getClientRects().length`), 'cancel dismissed');
  await refresh(); await poll(async () => (await fixture('calls()')).length === 4, 'late read');
  await fixture('set({selected:false})');
  await poll(async () => (await fixture('calls()'))[3].aborted, 'hidden page cancellation');
  await fixture('finish(3,"55.00")');
  await fixture('set({selected:true})');
  await poll(() => evaluate(`!!document.querySelector('#form_main_customer') && !document.querySelector('#form_main_amount')?.disabled`), 'restored form');
  assert.equal((await fixture('input()')).amount, '9007199254740993.12');
  await poll(() => evaluate(`!document.querySelector('${modal}')?.getClientRects().length`), 'confirmation closed');
  const fill = (selector, value) => evaluate(`(() => {const e = document.querySelector(${JSON.stringify(selector)}); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,${JSON.stringify(value)}); e.dispatchEvent(new Event('input',{bubbles:true}));})()`);
  const hiddenModal = () => poll(() => evaluate(`!document.querySelector('${modal}')?.getClientRects().length`), 'confirmation closed');
  await fill('#form_main_amount', '42.00');
  await poll(async () => (await fixture('input()')).amount === '42.00', 'manual edit');
  await textClick('button', '保存');
  await poll(async () => (await fixture('writes()')).length === 3, 'manual value save');
  assert.deepEqual((await fixture('writes()'))[2].mappings.confirmations[0].applyDefaultMappingIds, []);
  await textClick('button', '附加信息');
  await poll(() => evaluate(`!!document.querySelector('#form_secondary_customer')`), 'secondary scene');
  await refresh('#form_secondary_customer');
  await poll(async () => (await fixture('calls()')).length === 5, 'nested observation');
  await fixture('finish(4,"5.00",8)');
  await poll(() => evaluate(`!!document.querySelector('${modal}')?.getClientRects().length`), 'nested confirmation');
  await evaluate(`document.querySelector('${modal} .ant-table-tbody input[type=checkbox]:not(:disabled)').click()`);
  await textClick(modal + ' button', '确认带入'); await hiddenModal();
  assert.equal((await fixture('input()')).amount, '42.00', 'nested scene keeps parent unchanged');
  await textClick('.sp-form-runtime-dialog button', '取消');
  await poll(() => evaluate(`!!document.querySelector('.sp-form-runtime-discard .ant-modal')?.getClientRects().length`), 'discard confirmation');
  await textClick('.sp-form-runtime-discard button', '放弃修改');
  await poll(() => evaluate(`!document.querySelector('#form_main_amount').disabled`), 'main scene restored');
  await textClick('button', '保存');
  await poll(async () => (await fixture('writes()')).length === 4, 'save after nested cancel');
  assert.equal((await fixture('writes()'))[3].mappings.confirmations[0].observation.targetVersion, 7);
  await evaluate(`document.querySelector('button[aria-label="添加明细"]').click()`);
  await poll(async () => (await fixture('input()')).lines?.length === 1, 'new detail');
  const rowKey = (await fixture('input()')).lines[0].clientId;
  assert.ok(rowKey);
  const openSelect = async selector => {
    const point = await evaluate(`(() => {const e = document.querySelector(${JSON.stringify(selector)}); e.scrollIntoView({block:'center'}); const r=e.getBoundingClientRect(); return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
    await send('Input.dispatchMouseEvent',{type:'mousePressed',...point,button:'left',clickCount:1});
    await send('Input.dispatchMouseEvent',{type:'mouseReleased',...point,button:'left',clickCount:1});
  };
  await openSelect('.sp-form-detail .ant-select');
  await poll(() => evaluate(`!!document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')`), 'detail candidates');
  await textClick('.ant-select-dropdown .ant-select-item-option', '客户2');
  try {await poll(async () => (await fixture('calls()')).length === 6, 'detail selection observation');}
  catch (cause) {console.log('DETAIL DEBUG', await fixture('calls()'), await fixture('input()'), await evaluate('document.body.innerText'), errors); throw cause;}
  assert.deepEqual((await fixture('calls()'))[5].sourceRow, {newDetailRow: true});
  await fixture('finish(5,"6.00")');
  await poll(() => evaluate(`!!document.querySelector('${modal}')?.getClientRects().length`), 'detail confirmation');
  await textClick(modal + ' button', '确认带入'); await hiddenModal();
  await poll(async () => (await fixture('input()')).lines[0].values.amount === '6.00', 'empty detail default selected');
  await evaluate(`document.querySelector('button[aria-label="添加明细"]').click()`);
  await poll(async () => (await fixture('input()')).lines.length === 2, 'prepend detail');
  await textClick('button', '保存');
  await poll(async () => (await fixture('writes()')).length === 5, 'save reordered detail');
  saved = (await fixture('writes()'))[4];
  const detail = saved.mappings.confirmations.find(item => item.detailId === 'lines');
  assert.equal(detail.rowIndex, 1); assert.equal(detail.sourceRowId, undefined);
  assert.equal(saved.values.lines[1].clientId, undefined);
  assert.equal(saved.values.lines[1].values.amount, '6.00');
  assert.equal(saved.values.lines[1].values.name, undefined);
  await refresh(); await poll(async () => (await fixture('calls()')).length === 7, 'invalid response observation');
  await fixture('finish(6,"2.00",7,true)');
  await poll(() => evaluate(`document.body.innerText.includes('无法确认关联带出值')`), 'malformed response rejected');
  assert.equal((await fixture('input()')).amount, '42.00');
  await textClick('button', '附加信息');
  await poll(() => evaluate(`!!document.querySelector('#form_secondary_customer')`), 'secondary reopen');
  await refresh('#form_secondary_customer'); await poll(async () => (await fixture('calls()')).length === 8, 'nested apply observation');
  await fixture('finish(7,"8.00",8)');
  await poll(() => evaluate(`!!document.querySelector('${modal}')?.getClientRects().length`), 'nested apply confirmation');
  await textClick(modal + ' button', '确认带入'); await hiddenModal();
  await textClick('.sp-form-runtime-dialog button', '确认');
  await poll(() => evaluate(`!document.querySelector('#form_main_amount').disabled`), 'nested applied');
  await textClick('button', '保存');
  await poll(async () => (await fixture('writes()')).length === 6, 'save nested confirmation');
  assert.equal((await fixture('writes()'))[5].mappings.confirmations.find(item => !item.detailId).observation.targetVersion, 8);
  await evaluate(`document.querySelector('#form_main_customer').closest('.ant-select').querySelector('.ant-select-clear').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
  await poll(async () => (await fixture('calls()')).length === 9, 'clear reference observation');
  assert.equal((await fixture('calls()'))[8].target, null);
  await fixture('finish(8)');
  await poll(() => evaluate(`!!document.querySelector('${modal}')?.getClientRects().length`), 'clear confirmation');
  await textClick(modal + ' button', '确认带入'); await hiddenModal();
  assert.equal((await fixture('input()')).customer, null);
  assert.equal((await fixture('input()')).amount, '42.00');
  await textClick('button', '保存');
  await poll(async () => (await fixture('writes()')).length === 7, 'save clear');
  assert.equal((await fixture('writes()'))[6].mappings.confirmations.find(item => !item.detailId).observation.targetRecordId, null);
  await refresh(); await poll(async () => (await fixture('calls()')).length === 10, 'scope preparation');
  await evaluate(`localStorage.setItem('sp.tenantId','other-tenant'); window.dispatchEvent(new CustomEvent('sp-set-tenant',{detail:'other-tenant'}));`);
  await poll(async () => (await fixture('calls()'))[9].aborted, 'scope cancellation');
  await fixture('finish(9)');
  await hiddenModal();
  await textClick('button', '保存');
  await poll(async () => (await fixture('writes()')).length === 8, 'save under changed owner');
  assert.equal((await fixture('writes()'))[7].mappings, undefined, 'old confirmations are never reused in a new authorization scope');
  await fixture('edit({customer:"aaaaaaaa-1111-2222-3333-000000000001",lines:[]})');
  await fixture('set({preview:true})');
  await poll(async () => (await fixture('previews()')).length === 1, 'snapshot-only preview');
  assert.equal((await fixture('previews()'))[0].values.name, undefined);
  await fixture('finishPreview(0,"SERVER-PREVIEW")');
  await poll(() => evaluate(`document.querySelector('#form_main_name')?.textContent === 'SERVER-PREVIEW' || document.querySelector('#form_main_name')?.value === 'SERVER-PREVIEW'`), 'authoritative snapshot preview');
  await refresh(); await poll(async () => (await fixture('calls()')).length === 11, 'preview refresh observation');
  await fixture('finish(10,"8.00",9)');
  await poll(() => evaluate(`!!document.querySelector('${modal}')?.getClientRects().length`), 'preview refresh confirm');
  await textClick(modal + ' button', '确认带入'); await hiddenModal();
  await poll(async () => (await fixture('previews()')).length === 2, 'confirmed version triggers preview');
  assert.equal((await fixture('previews()'))[1].mappings.confirmations[0].observation.targetVersion, 9);
  assert.equal((await fixture('previews()'))[1].values.name, undefined);
  await fixture('finishPreview(1,"VERSION-NINE")');
  await poll(() => evaluate(`document.querySelector('#form_main_name')?.textContent === 'VERSION-NINE' || document.querySelector('#form_main_name')?.value === 'VERSION-NINE'`), 'confirmed preview displayed');
  await fill('#form_main_amount','43.00');
  await poll(async () => (await fixture('previews()')).length === 3, 'changed preview');
  await fill('#form_main_amount','44.00');
  await poll(async () => (await fixture('previews()'))[2].aborted, 'stale preview aborted');
  await fixture('finishPreview(2,"STALE-VALUE")');
  assert.equal(await evaluate(`document.body.innerText.includes('STALE-VALUE')`), false);
  await poll(async () => (await fixture('previews()')).length === 4, 'replacement preview');
  await fixture('finishPreview(3,"LATEST-VALUE")');
  await poll(() => evaluate(`document.querySelector('#form_main_name')?.textContent === 'LATEST-VALUE' || document.querySelector('#form_main_name')?.value === 'LATEST-VALUE'`), 'latest preview displayed');
  assert.equal((await fixture('input()')).amount, '44.00');
  await refresh(); await poll(async () => (await fixture('calls()')).length === 12, 'cancelable preparation');
  await textClick('.ant-alert button', '取消');
  await poll(async () => (await fixture('calls()'))[11].aborted, 'preparation cancelled');
  await fixture('finish(11,"1.00",10)'); await hiddenModal();
  assert.equal((await fixture('input()')).amount, '44.00');
  await textClick('button', '附加信息');
  await poll(() => evaluate(`!!document.querySelector('#form_secondary_amount')`), 'dirty scene owner');
  await fill('#form_secondary_amount', '45.00');
  await textClick('.sp-form-runtime-dialog button', '取消');
  await poll(() => evaluate(`!!document.querySelector('.sp-form-runtime-discard .ant-modal')?.getClientRects().length`), 'dirty scene discard');
  await evaluate(`localStorage.setItem('sp.tenantId','final-tenant'); window.dispatchEvent(new CustomEvent('sp-set-tenant',{detail:'final-tenant'}));`);
  await poll(() => evaluate(`!document.querySelector('.sp-form-runtime-discard .ant-modal')?.getClientRects().length && !document.querySelector('#form_main_amount')?.disabled`), 'owner change closes every scene overlay');
  assert.equal((await fixture('input()')).amount, '44.00');
  await fixture('set({fixed:true,preview:false})');
  await poll(() => evaluate(`[...document.querySelectorAll('button')].some(button => button.textContent.trim()==='保存' && button.disabled)`), 'fixed reference requires snapshot confirmation');
  await textClick('button', '确认关联带出值');
  await poll(async () => (await fixture('calls()')).length === 13, 'fixed customer observation');
  assert.equal((await fixture('calls()'))[12].target, 'aaaaaaaa-1111-2222-3333-000000000001');
  await fixture('finish(12,"11.00",10)');
  await poll(() => evaluate(`!!document.querySelector('${modal}')?.getClientRects().length`), 'fixed customer review');
  await textClick(modal + ' button', '确认带入'); await hiddenModal();
  await textClick('button', '保存');
  await poll(async () => (await fixture('writes()')).length === 9, 'fixed customer create confirmation');
  const fixed = (await fixture('writes()'))[8];
  assert.equal(fixed.mappings.confirmations[0].observation.targetVersion, 10);
  assert.deepEqual(fixed.mappings.confirmations[0].applyDefaultMappingIds, []);
  assert.equal(fixed.values.amount, '44.00');
  assert.deepEqual(errors, []);
  console.log(JSON.stringify({passed: 19, scenarios: ['actual runtime observation and save lock', 'unselected defaults preserve input', 'selected exact default reaches submit', 'cancel preserves parent', 'retained page fences late response', 'manual edit drops default intent', 'nested cancel restores observation', 'detail selection applies empty default', 'prepend resolves stable row identity at submit', 'malformed response is rejected', 'nested apply propagates same-target version refresh', 'explicit unlink preserves unchecked defaults', 'authorization scope discards old confirmations', 'snapshot-only preview strips editable copies', 'same-target version confirmation triggers authorized preview', 'stale preview never replaces current outputs', 'cancel preparation releases the form and fences late results', 'scope change disposes dirty scene and discard state', 'fixed inverse parent uses the standard review and preserves manual values']}));

} finally {
  socket?.close();
  if (browser && browser.exitCode === null) {browser.kill('SIGTERM'); await new Promise(resolve => browser.once('exit', resolve));}
  await server?.server.close();
  await rm(temporary, {recursive: true, force: true, maxRetries: 5, retryDelay: 100});
}
