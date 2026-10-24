import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';

const field = (fieldId, label, type = 'DECIMAL', extra = {}) => ({fieldId, storageCode: fieldId,
  label, storageLabel: label, type, storageMode: 'STORED', submitRequired: true, storageRequired: false,
  length: type === 'STRING' ? 80 : null, precision: type === 'DECIMAL' ? 24 : null,
  scale: type === 'DECIMAL' ? 2 : null, choices: [], ...extra});
const node = (componentId, componentType, extra = {}) => ({componentId, componentType,
  componentVersion: '1', columnSpan: 12, readOnly: false, properties: {}, children: [], ...extra});

/** This isolated page exercises publication maintenance; it never changes other business forms. */
export function recoveryDocument(expression) {
  const fields = [field('subject', '运维验收编号', 'STRING'), field('quantity', '数量'), field('rate', '比率'),
    field('total', '计算结果', 'DECIMAL', expression ? {storageMode: 'COMPUTED',
      calculation: {languageVersion: '1', expression}} : {})];
  return {contractVersion: '1', fields, details: [], indexes: [], rules: [], constraints: [], scenes: [
    {sceneId: 'main', code: 'main', name: '发布恢复验收', type: 'MAIN', root: node('root', 'layout.form',
      {columnSpan: 24, children: [...fields.map(f => node('input_' + f.fieldId,
        f.type === 'STRING' ? 'field.text' : 'field.number', {fieldId: f.fieldId})), node('save', 'action.save')]})},
  ]};
}

/** Save every accepted command before advancing, so interrupted verification can be resumed. */
export async function verifyPublicationRecovery({call, request, appId, state, save, restart, progress = console.log}) {
  const check = async (name, detail = {}) => {state.checks ??= []; state.checks.push({name, ...detail}); await save(); progress('PASS ' + name);};
  if (!state.pageId) {
    state.pageId = (await call(`/app/applications/${appId}/pages`, 'POST',
      {name: '发布恢复与权限验收', description: '隔离的合成数据，验证分批回填与取消恢复。'})).id; await save();
  }
  if (!state.formId) {
    state.formId = (await call(`/app/applications/${appId}/forms`, 'POST',
      {code: 'cal_publication_recovery', name: '发布恢复与权限验收', description: '运维故障恢复验证'})).id; await save();
  }
  const base = `/app/applications/${appId}/forms/${state.formId}`;
  const runtime = `/app/runtime/applications/${appId}/pages/${state.pageId}`, records = runtime + '/records';
  state.runtime = runtime; await save();
  async function freeze(stage, expression) {
    const item = state[stage] ??= {key: randomUUID(), steps: []}; await save();
    if (!item.version) {
      let draft = await call(base + '/draft');
      draft = await call(base + '/draft', 'PUT', {expectedRevision: draft.revision, document: recoveryDocument(expression)});
      item.version = (await call(base + '/versions', 'POST', {expectedRevision: draft.revision})).version; await save();
    }
    return item;
  }
  async function advance(item, action = 'publish') {
    const path = action === 'publish' ? base + `/versions/${item.version}/publication`
      : base + `/releases/${item.releaseId}/${action}`;
    const result = await call(path, 'POST', action === 'publish' ? {pageId: state.pageId, idempotencyKey: item.key} : undefined);
    item.steps.push(result); item.releaseId ??= result.pageBinding?.id; item.last = result; await save();
    return result;
  }
  async function finish(item, action = 'publish', target = 'ACTIVE') {
    for (let i = 0; i < 150; i++) {
      const result = await advance(item, action);
      if (result.pageBinding?.status === target) return result;
      assert(!result.inspection?.issues?.length, 'Unexpected publication inspection failure'); await delay(100);
    }
    assert.fail('Publication did not reach ' + target);
  }
  const initial = await freeze('initial');
  if (!initial.complete) {await finish(initial); initial.complete = true; await save();}
  state.seed ??= [];
  for (let i = state.seed.length; i < 65; i++) {
    const command = {runtime: {formId: state.formId, formVersion: initial.version}, state: 'DRAFT',
      idempotencyKey: randomUUID(), values: {subject: 'RECOVERY-' + String(i).padStart(3, '0'),
        quantity: String(i + 1), rate: i === 32 ? '0' : '2', total: '999.99'}};
    const result = await call(records, 'POST', command); state.seed.push({command, result}); await save();
  }
  const readAll = () => call(records + '/query', 'POST', {page: 0, size: 100, sorts: [{fieldId: 'subject', direction: 'ASC'}]});
  if (!state.before) {state.before = await readAll(); assert.equal(state.before.total, 65); await save();}
  const backfill = await freeze('backfill', '[quantity]*[rate]');
  if (!backfill.complete) {
    await finish(backfill); state.after = await readAll();
    assert.equal(state.after.total, 65);
    for (const row of state.after.records) {
      const old = state.before.records.find(r => r.id === row.id); assert(old);
      assert.equal(Number(row.values.total), Number(old.values.quantity) * Number(old.values.rate));
      assert.equal(row.version, old.version + 1);
    }
    assert(backfill.steps.some(r => r.progress?.phase === 'STAGING'));
    assert(backfill.steps.some(r => r.progress?.phase === 'APPLYING'));
    backfill.complete = true; await check('ordinary-to-computed backfill of 65 existing rows', {version: backfill.version});
  }
  const cancel = await freeze('cancel', '[quantity]*[rate]+100');
  if (!cancel.complete) {
    if (!cancel.partialApplied) {
      for (let i = 0; i < 100; i++) {
        const result = await advance(cancel);
        assert.notEqual(result.pageBinding?.status, 'ACTIVE', 'Must interrupt before activation');
        if (result.progress?.phase === 'APPLYING' && BigInt(result.progress.completedRecords) > 0n
          && BigInt(result.progress.completedRecords) < BigInt(result.progress.totalRecords)) {
          cancel.partialApplied = true; await save(); break;
        }
      }
      assert(cancel.partialApplied);
    }
    if (!cancel.maintenanceRead) {
      const blocked = await request(records + '/query', 'POST', {page: 0, size: 10});
      assert([409, 503].includes(blocked.status), 'Maintenance must reject mixed-version reads');
      cancel.maintenanceRead = {status: blocked.status, code: JSON.parse(blocked.body).code}; await save();
    }
    const cancelled = await finish(cancel, 'cancel', 'CANCELLED'); assert(!cancelled.progress);
    const restored = await readAll(); assert.deepEqual(restored, state.after);
    assert.equal((await call(runtime)).formVersion, backfill.version);
    if (!cancel.steps.some(r => r.progress?.phase === 'RESTORING')) {
      // A background worker may finish recovery after an interrupted test. Keep that evidence,
      // then use a new immutable version to observe the intermediate restoration response too.
      state.recoveredCancellationAttempts ??= []; state.recoveredCancellationAttempts.push(cancel);
      assert(state.recoveredCancellationAttempts.length <= 3, 'Unable to observe cancellation batches');
      delete state.cancel; await save();
      return verifyPublicationRecovery({call, request, appId, state, save, restart, progress});
    }
    cancel.complete = true; await check('cancel partial apply restores exact values, versions and audit metadata',
      {releaseId: cancel.releaseId, maintenance: cancel.maintenanceRead});
  }
  const failure = await freeze('failure', '[quantity]/[rate]');
  if (!failure.complete) {
    if (!failure.rejected) {
      for (let i = 0; i < 100; i++) {
        const response = await request(base + `/versions/${failure.version}/publication`, 'POST',
          {pageId: state.pageId, idempotencyKey: failure.key});
        if (response.status >= 400) {
          assert([400, 409, 422].includes(response.status));
          const error = JSON.parse(response.body);
          assert.notEqual(error.code, 'PUBLICATION_IN_PROGRESS', 'Publication contention is not a calculation failure');
          failure.rejected = {status: response.status, error}; await save(); break;
        }
        const payload = JSON.parse(response.body), result = Object.hasOwn(payload, 'data') ? payload.data : payload;
        failure.steps.push(result); failure.releaseId ??= result.pageBinding?.id; await save();
        if (result.inspection?.issues?.length) {failure.rejected = result.inspection; await save(); break;}
        assert.notEqual(result.pageBinding?.status, 'ACTIVE');
      }
      assert(failure.rejected, 'Division by zero in an existing row must prevent activation');
    }
    assert(failure.releaseId, 'Persist release identity before failure');
    await finish(failure, 'cancel', 'CANCELLED'); assert.deepEqual(await readAll(), state.after);
    failure.complete = true; await check('failing existing row prevents activation and cancellation preserves prior data');
  }
  const recovery = await freeze('restart', '[quantity]*[rate]+1');
  if (!recovery.complete) {
    if (!recovery.interrupted) {
      const result = await advance(recovery); assert(result.progress); assert.notEqual(result.pageBinding?.status, 'ACTIVE');
      recovery.interrupted = true; await save(); await restart();
    }
    await finish(recovery, 'resume');
    const after = await readAll();
    for (const row of after.records) {
      const old = state.after.records.find(r => r.id === row.id);
      assert.equal(Number(row.values.total), Number(old.values.total) + 1);
      assert.equal(row.version, old.version + 1);
    }
    recovery.complete = true; state.final = after; await check('service restart resumes original intent and backfills each row exactly once');
  }
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
}
