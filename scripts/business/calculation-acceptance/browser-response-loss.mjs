import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';

/** Drop an actual successful update response after commit, then use the platform retry control. */
export async function verifyActualResponseLoss({evaluate, poll, click, fill, send, on, writes, screenshot,
  call, request, spec, sample, state, save}) {
  if (state.passed) return;
  const endpoint = spec.runtime + '/records';
  state.create ??= {runtime: {formId: spec.formId, formVersion: spec.version}, state: 'DRAFT',
    idempotencyKey: randomUUID(), values: {function_name: sample.name, expression: sample.expression,
      expected: sample.expected, result_type: sample.type, reviewer: '响应丢失验收', conclusion: '临时响应恢复 ' + randomUUID()}};
  await save();
  if (!state.recordId) {state.recordId = (await call(endpoint, 'POST', state.create)).recordId; await save();}
  const path = endpoint + '/' + state.recordId;
  const before = await call(path);
  assert.equal(before.state, 'DRAFT');
  await click('.ant-menu-submenu-title', '计算引擎业务验收');
  await click('.ant-menu-item', spec.name);
  const root = '.nb-tab-panel-active', drawer = '.sp-simple-table-drawer.ant-drawer-open';
  const wasCards = await evaluate(`!!document.querySelector('${root} .sp-card-view')`);
  if (wasCards) await click(root + ' button', '切换为列表');
  const row = root + ` [data-row-key="${state.recordId}"]`;
  await poll(() => evaluate(`!!document.querySelector('${row} input[type="checkbox"]')`), 'temporary record in actual list');
  await evaluate(`document.querySelector('${row} input[type="checkbox"]').click()`);
  await click(root + ' button', '编辑');
  await fill(drawer + ' #form_main_reviewer', '响应恢复复核・日本語😀');
  let dropped = false, interceptionError;
  on('Fetch.requestPaused', async event => {
    try {
      if (!dropped && event.request.method === 'PUT' && new URL(event.request.url).pathname === path
          && event.responseStatusCode === 200) {
        dropped = true;
        state.interceptedCommand = JSON.parse(event.request.postData); await save();
        await send('Fetch.failRequest', {requestId: event.requestId, errorReason: 'ConnectionClosed'});
      } else await send('Fetch.continueResponse', {requestId: event.requestId});
    } catch (error) {interceptionError = error;}
  });
  await send('Fetch.enable', {patterns: [{urlPattern: '*' + path, requestStage: 'Response'}]});
  try {
    await click(drawer + ' button', '保存草稿');
    await poll(() => dropped || interceptionError, 'successful response intercepted after commit');
    if (interceptionError) throw interceptionError;
    await poll(() => evaluate(`[...document.querySelectorAll('${drawer} button')].some(b=>b.textContent.replaceAll(' ','')==='重试'&&!b.disabled)`), 'uncertain result exposes platform retry');
    const committed = await call(path);
    assert.equal(committed.version, before.version + 1);
    assert.equal(committed.values.reviewer, '响应恢复复核・日本語😀');
    assert.equal(committed.values.actual, sample.expected);
    assert.equal(await evaluate(`!!document.querySelector('${drawer} input#form_main_reviewer:not([disabled])')`), false,
      'The captured command is immutable until its outcome is resolved');
    state.committedBeforeRetry = committed; await save();
    await screenshot('calculation-committed-response-lost');
    await send('Fetch.disable');
    await click(drawer + ' button', '重试');
    await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'original update receipt recovered');
    const after = await call(path);
    assert.deepEqual(after, committed, 'Retry must not advance values, audit or aggregate version again');
    const attempts = writes.filter(write => write.method === 'PUT' && write.path === path);
    assert.equal(attempts.length, 2); assert.deepEqual(attempts[0].body, attempts[1].body);
    assert(!Object.hasOwn(attempts[0].body.values, 'actual'));
    state.retryCommandEqual = true; state.finalVersion = after.version; await save();
  } finally {await send('Fetch.disable');}
  state.deleteKey ??= randomUUID(); await save();
  const latest = await call(path);
  await call(path + '?' + new URLSearchParams({formId: spec.formId, formVersion: String(spec.version),
    expectedVersion: String(latest.version), idempotencyKey: state.deleteKey}), 'DELETE');
  assert.equal((await request(path)).status, 404);
  if (wasCards) await click(root + ' button', '切换为卡片');
  state.passed = true; state.temporaryRecordRemoved = true; state.completedAt = new Date().toISOString(); await save();
  console.log('PASS: real committed response loss, immutable pending form, exact command retry and no duplicate update');
}
