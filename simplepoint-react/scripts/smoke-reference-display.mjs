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
const temporary = await mkdtemp(path.join(tmpdir(), 'simplepoint-reference-display-'));
let server, browser, socket;
const errors = [];
const poll = async (read, description) => {
  const until = Date.now() + 30000;
  while (Date.now() < until) {const result = await read(); if (result) return result; await delay(30);}
  throw Error(`Timed out: ${description}`);
};
try {
  const rsbuild = await createRsbuild({cwd: root, rsbuildConfig: {plugins: [pluginReact()],
    source: {entry: {index: path.join(root, 'scripts/reference-display-harness.tsx')}},
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
  const fixture = expression => evaluate(`window.referenceDisplay.${expression}`);
  const text = () => evaluate(`document.getElementById('fixture')?.textContent ?? ''`);
  const count = async () => (await fixture('calls()')).length;
  const finishFrom = async (start, label) => {
    const end = await count();
    for (let index = start; index < end; index++) await fixture(`finish(${index}, ${JSON.stringify(label)})`);
    return end;
  };

  await poll(async () => (await text()).includes('aaaaaaaa-1111'), 'snapshot renderer');
  await poll(() => evaluate(`document.querySelectorAll('.sp-form-detail .ant-table-row').length === 40`), 'forty historical detail rows');
  assert.equal(await count(), 0, 'snapshot must ignore the outer live adapter');
  await fixture(`set({mode:'frozen'})`);
  await poll(async () => (await text()).includes('历史名称-40'), 'frozen root and forty detail labels');
  assert.equal(await count(), 0, 'frozen labels must override even an explicitly supplied live adapter');
  await fixture(`set({mode:'revoked'})`);
  await poll(async () => !(await text()).includes('历史名称-'), 'revoked snapshot labels disappear');
  assert.equal(await count(), 0, 'empty historical metadata must never fall back to live reads');
  await fixture(`set({mode:'live'})`);
  await poll(async () => (await fixture('calls()')).flatMap(call => call.ids).length === 41, 'root and forty detail references');
  const afterLive = await finishFrom(0, '客户・日本語😀');
  assert(afterLive <= 3, 'mounted references must be coalesced, not queried per cell');
  await poll(async () => (await text()).includes('客户・日本語😀40'), 'live detail label');
  assert.equal(await evaluate(`document.querySelectorAll('#fixture input, #fixture [role="combobox"]').length`), 0);

  await fixture(`set({mode:'values',generation:1})`);
  await poll(async () => await count() > afterLive, 'new owner');
  assert.equal(await count(), afterLive + 1, 'forty mounted values share one batch');
  assert.equal((await fixture('calls()'))[afterLive].ids.length, 40);
  assert.equal(await evaluate(`document.getElementById('masked').textContent`), '***');
  await fixture(`set({selected:false})`);
  await poll(async () => (await fixture('calls()'))[afterLive].aborted, 'inactive owner cancels its request');
  await fixture(`finish(${afterLive},'LATE-SECRET')`);
  assert(!(await text()).includes('LATE-SECRET'));
  await fixture(`set({selected:true})`);
  await poll(async () => await count() > afterLive + 1, 'reactivated owner rechecks');
  let completed = await finishFrom(afterLive + 1, 'VISIBLE');
  await poll(async () => (await text()).includes('VISIBLE39'), 'reactivated labels');

  await evaluate(`localStorage.setItem('sp.tenantId','another-tenant'); window.dispatchEvent(new Event('sp-set-tenant'));`);
  await poll(async () => await count() > completed, 'scope-specific directory');
  assert(!(await text()).includes('VISIBLE'), 'previous authorization labels cleared immediately');
  completed = await finishFrom(completed, null);
  await poll(async () => (await text()).includes('名称不可用'), 'missing and unreadable references share a placeholder');

  await fixture(`set({generation:2})`);
  await poll(async () => await count() > completed, 'replacement runtime');
  let next = await count();
  for (let index = completed; index < next; index++) await fixture(`fail(${index})`);
  await poll(async () => (await text()).includes('加载失败'), 'directory failure is visible');
  assert(!(await text()).includes('VISIBLE'));
  await evaluate(`document.querySelector('#fixture button').click()`);
  await poll(async () => await count() > next, 'shared action retries a failed label');
  await finishFrom(next, 'RETRY');
  await poll(async () => (await text()).includes('RETRY'), 'retry success');

  completed = await count();
  await fixture(`set({mode:'snapshot'})`);
  await poll(async () => (await text()).includes('aaaaaaaa-1111'), 'return to immutable view');
  await delay(100);
  assert.equal(await count(), completed, 'historical rendering never resolves current names');

  await fixture(`set({mode:'inspector',generation:3})`);
  await poll(async () => await count() > completed, 'inspector source label');
  let labelIndex = await count() - 1;
  await fixture(`finish(${labelIndex},'NO-ACTION',false)`);
  await poll(async () => (await text()).includes('NO-ACTION'), 'readable label without action permission');
  assert.equal(await evaluate(`document.querySelectorAll('#fixture button').length`), 0);
  await fixture(`set({generation:4})`);
  await poll(async () => await count() > labelIndex + 1, 'new capability read');
  labelIndex = await count() - 1;
  await fixture(`finish(${labelIndex},'OPEN',true)`);
  await poll(() => evaluate(`!!document.querySelector('#fixture button')`), 'server-derived detail action');
  await fixture(`set({locked:true})`);
  await poll(() => evaluate(`!document.querySelector('#fixture button')`), 'busy form cannot open a reference');
  await fixture(`set({locked:false})`);
  await poll(() => evaluate(`!!document.querySelector('#fixture button')`), 'unlocked form restores authorized action');
  await evaluate(`document.querySelector('#fixture button').click();document.querySelector('#fixture button')?.click()`);
  await poll(async () => (await fixture('details()')).length === 1, 'single first-level read');
  await fixture('finishDetail(0)');
  await poll(async () => await count() > labelIndex + 1, 'nested reference label');
  labelIndex = await count() - 1;
  await fixture(`finish(${labelIndex},'NESTED',true)`);
  const drawer = '.sp-reference-inspector.ant-drawer-open';
  const action = `${drawer} button:not(.ant-drawer-close)`;
  await poll(() => evaluate(`!!document.querySelector('${action}')`), 'nested detail action');
  await fixture(`scene('aaaaaaaa-1111-2222-3333-000000000000','retained-scene')`);
  await evaluate(`document.querySelector('${action}').click()`);
  await poll(async () => (await fixture('details()')).length === 2, 'second-level read');
  await fixture('finishDetail(1)');
  await poll(async () => await count() > labelIndex + 1, 'second-level reference label');
  labelIndex = await count() - 1;
  await fixture(`finish(${labelIndex},'DEPTH-LIMIT',true)`);
  await poll(() => evaluate(`document.querySelector('${drawer}')?.textContent.includes('DEPTH-LIMIT')`), 'second detail rendered');
  assert.equal(await evaluate(`document.querySelectorAll('${action}').length`), 0, 'no third drawer without a page navigator');
  const lookupsBeforeReturn = await count();
  await evaluate(`document.querySelector('${drawer} .ant-drawer-close').click()`);
  await poll(async () => (await fixture('details()')).length === 3, 'parent reauthorized on return');
  assert.equal(await count(), lookupsBeforeReturn, 'nested reference reads wait for parent reauthorization');
  await fixture('finishDetail(2)');
  await poll(() => evaluate(`document.querySelector('${drawer} [data-inspector-scene]')?.textContent === 'retained-scene'`), 'parent scene retained');
  await evaluate(`document.querySelector('${drawer} .ant-drawer-close').click()`);
  await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'source restored');
  await poll(async () => await count() > labelIndex + 1, 'source hydration on return');
  labelIndex = await count() - 1;
  await fixture(`finish(${labelIndex},'REOPEN',true)`);
  await poll(() => evaluate(`!!document.querySelector('#fixture button')`), 'source action restored');
  await evaluate(`document.querySelector('#fixture button').click()`);
  await poll(async () => (await fixture('details()')).length === 4, 'pending detail read');
  await evaluate(`document.querySelector('${drawer} .ant-drawer-close').click()`);
  await poll(async () => (await fixture('details()'))[3].aborted, 'closing cancels detail read');
  await fixture('finishDetail(3)');
  await delay(100);
  assert.equal(await evaluate(`document.querySelectorAll('${drawer}').length`), 0, 'late detail cannot reopen a closed owner');

  completed = await count();
  await fixture(`set({generation:5})`);
  await poll(async () => await count() > completed, 'new owner for background cancellation');
  await fixture(`finish(${await count() - 1},'BACKGROUND',true)`);
  await poll(() => evaluate(`!!document.querySelector('#fixture button')`), 'foreground open action');
  await evaluate(`document.querySelector('#fixture button').click()`);
  await poll(async () => (await fixture('details()')).length === 5, 'background test detail request');
  await fixture(`set({selected:false})`);
  await poll(async () => (await fixture('details()'))[4].aborted, 'hidden page cancels detail');
  await fixture('finishDetail(4)');
  assert.equal(await evaluate(`document.querySelectorAll('${drawer}').length`), 0);
  await fixture(`set({selected:true})`);
  await poll(async () => (await fixture('details()')).length === 6, 'reactivation reauthorizes target');
  await evaluate(`localStorage.setItem('sp.tenantId','third-tenant'); window.dispatchEvent(new Event('sp-set-tenant'));`);
  await poll(async () => (await fixture('details()'))[5].aborted, 'scope change cancels old target');
  await fixture('finishDetail(5)');
  await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'scope change clears the inspector stack');

  completed = await count();
  await fixture(`set({generation:6})`);
  await poll(async () => await count() > completed, 'disabled-owner scenario directory');
  await fixture(`finish(${await count() - 1},'DISABLE',true)`);
  await poll(() => evaluate(`!!document.querySelector('#fixture button')`), 'disabled-owner action ready');
  await evaluate(`document.querySelector('#fixture button').click()`);
  await poll(async () => (await fixture('details()')).length === 7, 'disabled-owner detail request');
  await fixture(`set({enabled:false})`);
  await poll(async () => (await fixture('details()'))[6].aborted, 'unavailable source cancels target reads');
  await fixture('finishDetail(6)');
  await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'unavailable source closes target');
  assert.equal(await evaluate(`document.querySelector('[data-owner-selected]').textContent`), 'selected', 'owner remains usable to close its existing draft');

  completed = await count();
  await fixture(`set({enabled:true,mode:'editor',generation:7})`);
  await poll(() => evaluate(`!!document.querySelector('#fixture [role="combobox"]')`), 'shared reference editor');
  await poll(async () => await count() > completed, 'editor hydrates its selected value');
  let settled = completed;
  await poll(async () => {
    const end = await count();
    for (; settled < end; settled++) await fixture(`finish(${settled},'EDIT',true)`);
    return evaluate(`(() => {const button=[...document.querySelectorAll('#fixture button')].find(button=>button.textContent.includes('详情')); if (!button) return false; button.click(); return true;})()`);
  }, 'editor detail action');
  assert(await count() - completed <= 4, 'editor selection and current label reads stay bounded');
  await poll(async () => (await fixture('details()')).length === 8, 'editor uses the same inspector reader');
  await evaluate(`document.querySelector('${drawer} .ant-drawer-close').click()`);
  await poll(async () => (await fixture('details()'))[7].aborted, 'editor detail cancelled on close');
  await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'editor restored');

  completed = await count();
  await fixture(`set({mode:'inspector',generation:8,pageNavigation:true})`);
  for (let depth = 0; depth < 3; depth++) {
    await poll(async () => await count() > completed, `navigation label depth ${depth}`);
    completed = await count();
    await fixture(`finish(${completed - 1},'NAVIGATE-${depth}',true)`);
    const selector = depth === 0 ? '#fixture button' : action;
    await poll(() => evaluate(`!!document.querySelector('${selector}')`), `navigation action depth ${depth}`);
    await evaluate(`document.querySelector('${selector}').click()`);
    if (depth < 2) {
      await poll(async () => (await fixture('details()')).length === 9 + depth, `bounded drawer depth ${depth}`);
      await fixture(`finishDetail(${8 + depth})`);
    }
  }
  await poll(async () => (await fixture('pages()')).length === 1, 'third-level navigation delegates to owning platform');
  assert.equal((await fixture('details()')).length, 10, 'page handoff must not open a third drawer');
  assert.equal(await evaluate(`document.querySelectorAll('.sp-reference-inspector').length`), 2, 'only two retained drawer frames');

  await fixture(`set({mode:'context',generation:9})`);
  const openCandidate = async (detail = false) => {
    const selector = detail ? '#fixture .sp-form-detail .ant-select' : '#fixture .ant-select';
    await poll(() => evaluate(`!!document.querySelector('${selector}')`), 'contextual picker mounted');
    const point = await evaluate(`(() => {const element = document.querySelector('${selector}'); element.scrollIntoView({block:'center'});
      const rect = element.getBoundingClientRect(); return {x:rect.x+rect.width/2,y:rect.y+rect.height/2};})()`);
    await send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', clickCount: 1});
    await send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'left', clickCount: 1});
  };
  const candidateCount = async () => (await fixture('candidates()')).length;
  await openCandidate();
  await poll(async () => await candidateCount() === 1, 'root context request');
  assert.deepEqual((await fixture('candidates()'))[0].context, {fields: {region: {kind:'STRING',value:'华北'}}, row: {}});
  await fixture(`finishCandidate(0,'NORTH-CUSTOMER')`);
  await poll(() => evaluate(`document.body.textContent.includes('NORTH-CUSTOMER')`), 'root candidates');
  await evaluate(`Array.from(document.querySelectorAll('.ant-select-dropdown button')).find(button=>button.textContent.includes('加载更多'))?.click()`);
  await poll(async () => await candidateCount() === 2, 'second candidate page');
  assert.equal((await fixture('candidates()'))[1].page, 1);
  await evaluate(`window.referenceContext.set({...window.referenceContext.values(),region:'华南'})`);
  await poll(async () => (await fixture('candidates()'))[1].aborted, 'changed context cancels old page');
  await fixture(`finishCandidate(1,'STALE-NORTH')`);
  await openCandidate();
  await poll(async () => await candidateCount() === 3, 'replacement context starts fresh');
  assert.equal((await fixture('candidates()'))[2].page, 0);
  assert.equal((await fixture('candidates()'))[2].context.fields.region.value, '华南');
  assert.equal(await evaluate(`document.body.textContent.includes('STALE-NORTH')`), false);
  await fixture(`finishCandidate(2,'SOUTH-CUSTOMER',1)`);
  await poll(() => evaluate(`document.body.textContent.includes('SOUTH-CUSTOMER')`), 'new context candidates');
  await evaluate(`document.querySelector('#fixture .sp-form-detail .ant-table-row button')?.click()`);
  await openCandidate(true);
  await poll(async () => await candidateCount() === 4, 'current detail row candidate request');
  assert.deepEqual((await fixture('candidates()'))[3].context,
    {fields:{region:{kind:'STRING',value:'华南'}},row:{region:{kind:'STRING',value:'東京'}}});
  await evaluate(`document.querySelectorAll('#fixture .sp-form-detail .ant-table-row')[1].querySelector('button').click()`);
  await poll(async () => (await fixture('candidates()'))[3].aborted, 'switching rows cancels the previous picker');
  await fixture(`finishCandidate(3,'STALE-FIRST-ROW')`);
  await openCandidate(true);
  await poll(async () => await candidateCount() === 5, 'second row starts a new candidate request');
  assert.deepEqual((await fixture('candidates()'))[4].context,
    {fields:{region:{kind:'STRING',value:'华南'}},row:{region:{kind:'STRING',value:'大阪'}}});
  assert.equal(await evaluate(`document.body.textContent.includes('STALE-FIRST-ROW')`), false);
  await fixture(`set({selected:false})`);
  await poll(async () => (await fixture('candidates()'))[4].aborted, 'retained page context read cancelled');
  await fixture(`finishCandidate(4,'LATE-ROW')`);
  assert.equal(await evaluate(`document.body.textContent.includes('LATE-ROW')`), false);
  await evaluate(`window.referenceContext.set({...window.referenceContext.values(),region:''})`);
  await fixture(`set({selected:true})`);
  await poll(() => evaluate(`Array.from(document.querySelectorAll('#fixture [role="combobox"]')).length === 2 &&
    Array.from(document.querySelectorAll('#fixture [role="combobox"]')).every(input=>input.disabled)`), 'missing inputs disable root and row browsing');
  assert.equal(await candidateCount(), 5, 'missing inputs never become an unfiltered search');
  const selectedId = 'aaaaaaaa-1111-2222-3333-000000000500';
  await evaluate(`window.referenceContext.set({...window.referenceContext.values(),region:'华南',customer:'${selectedId}'})`);
  await poll(() => evaluate(`!document.querySelector('#fixture .ant-select-status-warning')`), 'initial selected record does not require review');
  await evaluate(`window.referenceContext.set({...window.referenceContext.values(),region:'华东'})`);
  await poll(() => evaluate(`!!document.querySelector('#fixture .ant-select-status-warning')`), 'changed dependencies request selection review');
  assert.equal(await evaluate(`window.referenceContext.values().customer`), selectedId, 'context changes never silently clear the saved identity');
  await evaluate(`Array.from(document.querySelectorAll('#fixture button')).find(button=>button.getAttribute('aria-label')==='保留关联')?.click()`);
  await poll(() => evaluate(`!document.querySelector('#fixture .ant-select-status-warning')`), 'user explicitly retains the current reference');
  assert.equal(await evaluate(`window.referenceContext.values().customer`), selectedId);
  assert.deepEqual(errors, []);
  console.log(JSON.stringify({passed: true, scope: 'isolated real browser components, not live business acceptance',
    cases: ['readonly root/detail labels', 'forty-row batching', 'masked non-read', 'retained owner cancellation',
      'late response fence', 'reactivation', 'authorization switch', 'unavailable placeholder', 'error/retry', 'snapshot isolation', 'frozen root/detail labels override live adapter', 'revoked snapshots never fall back to live reads',
      'server-derived detail action', 'double-click deduplication', 'two-level inspection', 'return reauthorization and scene retention', 'closed detail cancellation',
      'hidden owner cancels details', 'scope change clears stack and fences detail response',
      'busy form action lock', 'unavailable source clears inspector without trapping owner draft', 'editable reference inspector',
      'third-level handoff uses explicit domain page location without a third drawer',
      'root context uses declared inputs only', 'context changes cancel and reset candidate paging',
      'stale context results fenced', 'detail row and parent contexts are separate', 'row switching fences previous candidates', 'retained page cancels contextual reads',
      'missing dependencies disable browsing without querying unfiltered records', 'changed input requests review without discarding selected identity']}));
} finally {
  socket?.close();
  if (browser && browser.exitCode === null) {browser.kill('SIGTERM'); await new Promise(resolve => browser.once('exit', resolve));}
  await server?.server.close();
  await rm(temporary, {recursive: true, force: true, maxRetries: 5, retryDelay: 100});
}
