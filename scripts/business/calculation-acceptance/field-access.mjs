import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';

/** Publish operation policies on the isolated operations form, verify UI/API, then restore them. */
export async function verifyPublishedFieldAccess({call, request, appId, spec, state, save, inspect}) {
  const base = `/app/applications/${appId}/forms/${spec.formId}`, records = spec.runtime + '/records';
  if (state.passed) return;
  if (state.cleanupRestored) {
    state.previousAttempts ??= [];
    state.previousAttempts.push({restricted: state.restricted, restored: state.restored, checks: state.checks,
      browser: state.browser, cleanupRestored: true});
    delete state.restricted; delete state.restored; delete state.browser;
    state.cleanupRestored = false;
    await save();
  }
  state.original ??= await call(base + '/draft'); state.originalRuntime ??= await call(spec.runtime);
  state.before ??= await call(records + '/query', 'POST', {page: 0, size: 100, sorts: [{fieldId: 'subject', direction: 'ASC'}]});
  assert.equal(state.before.total, 65); await save();
  async function publish(name, document) {
    const step = state[name] ??= {key: randomUUID()}; await save();
    if (!step.version) {
      const current = await call(base + '/draft');
      const draft = await call(base + '/draft', 'PUT', {expectedRevision: current.revision, document});
      step.version = (await call(base + '/versions', 'POST', {expectedRevision: draft.revision})).version; await save();
    }
    for (let i = 0; i < 150 && !step.active; i++) {
      const result = await call(base + `/versions/${step.version}/publication`, 'POST', {pageId: spec.pageId, idempotencyKey: step.key});
      assert(!result.inspection?.issues?.length);
      step.last = result; step.active = result.pageBinding?.status === 'ACTIVE'; await save();
      if (!step.active) await delay(100);
    }
    assert(step.active); return step.version;
  }
  const document = structuredClone(state.original.document);
  const subject = document.fields.find(field => field.fieldId === 'subject');
  // An optional hidden create field needs no fabricated default. Required calculation inputs stay writable on create.
  subject.submitRequired = false;
  subject.access = {readOnly: true, create: 'HIDDEN', edit: 'WRITE'};
  document.fields.find(field => field.fieldId === 'quantity').access = {readOnly: false, create: 'WRITE', edit: 'READ'};
  document.fields.find(field => field.fieldId === 'rate').access = {readOnly: false, create: 'WRITE', edit: 'HIDDEN'};
  try {
    const version = await publish('restricted', document), record = state.before.records[0];
    const before = await call(records + '/' + record.id), runtime = {formId: spec.formId, formVersion: version};
    state.checks = [];
    for (const [name, method, path, body] of [
      ['create-hidden', 'POST', records, {runtime, state: 'DRAFT', values: {subject: 'blocked', quantity: '1', rate: '2'}}],
      ['edit-global-read-only', 'PUT', records + '/' + record.id, {runtime, state: 'DRAFT', expectedVersion: before.version, values: {subject: 'blocked'}}],
      ['edit-read-only', 'PUT', records + '/' + record.id, {runtime, state: 'DRAFT', expectedVersion: before.version, values: {quantity: '2'}}],
      ['edit-hidden', 'PUT', records + '/' + record.id, {runtime, state: 'DRAFT', expectedVersion: before.version, values: {rate: '2'}}],
      ['computed-output', 'PUT', records + '/' + record.id, {runtime, state: 'DRAFT', expectedVersion: before.version, values: {total: '1'}}],
      ['old-page-policy-bypass', 'PUT', records + '/' + record.id, {runtime: {formId: spec.formId, formVersion: state.originalRuntime.formVersion},
        state: 'DRAFT', expectedVersion: before.version, values: {quantity: '2'}}],
    ]) {
      const result = await request(path, method, {...body, idempotencyKey: randomUUID()});
      assert([400, 403, 409].includes(result.status), name + ': ' + result.body);
      const error = JSON.parse(result.body); assert(error.messageKey);
      assert.deepEqual(await call(records + '/' + record.id), before);
      state.checks.push({name, status: result.status, code: error.code, messageKey: error.messageKey}); await save();
    }
    const empty = await request(records + '/preview', 'POST', {runtime, state: 'DRAFT', values: {}});
    state.emptyCreatePreview = {status: empty.status, response: JSON.parse(empty.body)}; await save();
    assert([200, 400].includes(empty.status));
    if (empty.status === 400) {
      assert(['RECORD_INVALID', 'VALIDATION_FAILED', 'RECORD_COMMAND_INVALID'].includes(state.emptyCreatePreview.response.code));
      assert(state.emptyCreatePreview.response.messageKey);
    }
    state.browser = await inspect(record.id, state.emptyCreatePreview); assert(state.browser.passed); await save();
  } finally {
    // Restore through immutable publication; never alter another service's tables or rewrite old snapshots.
    await publish('restored', state.original.document);
    assert.deepEqual((await call(base + '/draft')).document, state.original.document);
    const after = await call(records + '/query', 'POST', {page: 0, size: 100, sorts: [{fieldId: 'subject', direction: 'ASC'}]});
    assert.deepEqual(after.records.map(row => ({id: row.id, values: row.values})), state.before.records.map(row => ({id: row.id, values: row.values})));
    state.cleanupRestored = true; await save();
  }
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
  console.log('PASS published create-hidden/edit-read/edit-hidden/read-only policies, direct bypasses, actual UI and restoration');
}

export async function inspectPublishedFieldAccess({evaluate, poll, click, fill, send, on, failures, appId, spec, recordId, diagnostics, emptyCreatePreview}) {
  const root = '.nb-tab-panel-active', drawer = '.sp-simple-table-drawer.ant-drawer-open';
  const requests = new Map();
  on('Network.requestWillBeSent', ({requestId, request}) => {
    if (new URL(request.url).pathname === spec.runtime + '/records/preview') requests.set(requestId, {body: JSON.parse(request.postData)});
  });
  on('Network.responseReceived', ({requestId, response}) => {
    if (requests.has(requestId)) {
      const request = requests.get(requestId); request.status = response.status;
      if (response.status >= 400) void diagnostics?.(request);
    }
  });
  on('Network.loadingFinished', ({requestId}) => {
    const request = requests.get(requestId);
    if (request?.status >= 400) void send('Network.getResponseBody', {requestId})
      .then(response => diagnostics?.({...request, response: JSON.parse(response.body)}))
      .catch(error => diagnostics?.({captureFailed: String(error)}));
  });
  await evaluate(`location.hash=${JSON.stringify('#/app/runtime/' + appId + '/' + spec.pageId)}`);
  // The query defaults newest first, while the fixed record may be on another page: use the known last-created row instead.
  await poll(() => evaluate(`!!document.querySelector('${root} [data-row-key] input[type="checkbox"]')`), 'policy resource row');
  await evaluate(`document.querySelector('${root} [data-row-key] input[type="checkbox"]').click()`);
  await click(root + ' button', '编辑');
  await poll(() => evaluate(`!!document.querySelector('${drawer} #form_main_total')`), 'policy editor');
  for (const id of ['subject', 'quantity', 'total']) {
    assert.equal(await evaluate(`!!document.querySelector('${drawer} input#form_main_${id}')`), false);
    assert(await evaluate(`!!document.querySelector('${drawer} #form_main_${id}')`));
  }
  assert.equal(await evaluate(`!!document.querySelector('${drawer} #form_main_rate')`), false);
  const editPreview = await poll(() => [...requests.values()].find(item => item.body.recordId && item.status), 'read-only edit preview response');
  assert.equal(editPreview.status, 200, JSON.stringify(editPreview));
  await evaluate(`document.querySelector('${drawer} .ant-drawer-close').click()`);
  await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'read-only editor closed');
  await evaluate(`document.querySelector('${root} [data-row-key] input[type="checkbox"]:checked').click()`);
  await poll(() => evaluate(`!document.querySelector('${root} [data-row-key] input[type="checkbox"]:checked')`), 'selection cleared for create action');
  await click(root + ' button', '新增');
  await poll(() => evaluate(`!!document.querySelector('${drawer} #form_main_rate')`), 'create policy editor');
  assert(await evaluate(`!!document.querySelector('${drawer} input#form_main_quantity')`));
  assert.equal(await evaluate(`!!document.querySelector('${drawer} #form_main_subject')`), false);
  await fill(drawer + ' #form_main_quantity', '3');
  await fill(drawer + ' #form_main_rate', '2');
  await poll(() => evaluate(`Number(document.querySelector('${drawer} #form_main_total')?.textContent)===7`), 'permitted create inputs produce the trusted preview');
  await evaluate(`document.querySelector('${drawer} .ant-drawer-close').click()`);
  await click('.sp-simple-table-discard .ant-modal button', '放弃修改');
  await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'policy create closed');
  const emptyRequests = [...requests.values()].filter(item => item.status === 400 && !item.body.recordId && Object.keys(item.body.values).length === 0);
  if (emptyRequests.length) {
    assert.equal(emptyCreatePreview.status, 400, 'Only independently verified empty-input validation is expected');
    const expected = failures.filter(item => item.path === spec.runtime + '/records/preview' && item.status === 400);
    assert.equal(expected.length, emptyRequests.length);
    for (const failure of expected) failures.splice(failures.indexOf(failure), 1);
  }
  return {passed: true, editReadOnly: ['subject', 'quantity', 'total'], editHidden: ['rate'], createHidden: ['subject'],
    emptyPreview: emptyCreatePreview, validPreview: '7', discardedWithoutWrite: true};
}
