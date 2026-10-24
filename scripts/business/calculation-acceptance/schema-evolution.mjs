import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';
import {recoveryDocument} from './recovery.mjs';

/** Uses the normal APP publication and permission-bound DNA review; never writes target SQL. */
export async function verifyComputedColumnEvolution({call, request, deniedRequest, appId, recovery, state, save,
  progress = console.log}) {
  const base = `/app/applications/${appId}/forms/${recovery.formId}`;
  const runtime = recovery.runtime, records = runtime + '/records';
  const check = async (name, evidence = {}) => {
    state.checks ??= []; state.checks.push({name, ...evidence}); await save(); progress('PASS ' + name);
  };
  const readAll = () => call(records + '/query', 'POST', {page: 0, size: 100,
    sorts: [{fieldId: 'subject', direction: 'ASC'}]});
  if (!state.before) {state.before = await readAll(); assert.equal(state.before.total, 65); await save();}
  async function freeze(key, required) {
    const item = state[key] ??= {key: randomUUID(), steps: []}; await save();
    if (!item.version) {
      const document = recoveryDocument('[quantity]*[rate]+1');
      document.fields.push({...document.fields.find(f => f.fieldId === 'total'), fieldId: 'normalized_total',
        storageCode: 'normalized_total', label: '非空计算列', storageLabel: '非空计算列', storageRequired: required,
        calculation: {languageVersion: '1', expression: '[total]+10'}});
      document.scenes[0].root.children.splice(-1, 0, {componentId: 'input_normalized_total', componentType: 'field.number',
        componentVersion: '1', columnSpan: 12, readOnly: false, properties: {}, children: [], fieldId: 'normalized_total'});
      let draft = await call(base + '/draft');
      draft = await call(base + '/draft', 'PUT', {expectedRevision: draft.revision, document});
      item.version = (await call(base + '/versions', 'POST', {expectedRevision: draft.revision})).version; await save();
    }
    return item;
  }
  const path = item => base + `/versions/${item.version}/publication`;
  const command = item => ({pageId: recovery.pageId, idempotencyKey: item.key});
  async function finish(item) {
    for (let i = 0; i < 150; i++) {
      const result = await call(path(item), 'POST', command(item)); item.steps.push(result); await save();
      if (result.pageBinding?.status === 'ACTIVE') {item.complete = true; await save(); return;}
      assert(!result.inspection?.issues?.length); await delay(100);
    }
    assert.fail('Column evolution did not finish');
  }
  const nullable = await freeze('nullable', false);
  if (!nullable.complete) {
    await finish(nullable); state.backfilled = await readAll();
    assert.equal(state.backfilled.total, 65);
    for (const row of state.backfilled.records) {
      const old = state.before.records.find(r => r.id === row.id);
      assert.equal(Number(row.values.normalized_total), Number(old.values.total) + 10);
      assert.equal(row.values.total, old.values.total);
    }
    await check('new computed column backfills every existing aggregate before activation');
  }
  const required = await freeze('required', true);
  if (!required.reviewRequired) {
    const response = await request(path(required), 'POST', command(required));
    assert.equal(response.status, 409, response.body);
    const error = JSON.parse(response.body); assert.equal(error.code, 'PUBLICATION_REVIEW_REQUIRED');
    required.reviewRequired = error; await save();
    await check('NOT NULL tightening requires explicit migration review');
  }
  if (!state.modelId) {
    const page = await call('/dna/platform/dna/runtime/models?size=100&formId=' + recovery.formId);
    const found = page.content.filter(m => m.formId === recovery.formId && m.appId === appId);
    assert.equal(found.length, 1); state.modelId = found[0].id; await save();
  }
  const modelPath = '/dna/platform/dna/runtime/models/' + state.modelId;
  if (!required.migration) {
    const review = await call(modelPath + '/review');
    assert.equal(review.canPublish, true); assert.equal(review.plan.risk, 'CONDITIONAL');
    required.review = review; await save();
    const publish = {targetVersion: review.plan.targetVersion, expectedVersion: review.expectedVersion,
      actualChecksum: review.plan.actualChecksum, targetChecksum: review.plan.targetChecksum};
    const denied = await deniedRequest(modelPath + '/publish', 'POST', publish);
    assert([403, 404].includes(denied.status));
    const stale = await request(modelPath + '/publish', 'POST', {...publish, expectedVersion: publish.expectedVersion + 1});
    assert.equal(stale.status, 409); required.deniedStatus = denied.status; required.staleStatus = stale.status; await save();
    required.migration = await call(modelPath + '/publish', 'POST', publish); await save();
    assert.equal(required.migration.status, 'ACTIVE');
    await check('authorized frozen review executes through standard DNA migration', {migrationId: required.migration.migrationId});
  }
  if (!required.complete) await finish(required);
  const after = await readAll(); assert.equal(after.total, 65);
  for (const row of after.records) {
    assert.equal(Number(row.values.normalized_total), Number(row.values.total) + 10);
    assert.equal(Number(row.values.total), Number(row.values.quantity) * Number(row.values.rate) + 1);
  }
  const model = await call(modelPath), schema = JSON.parse(model.schemaJson);
  assert.equal(schema.fields.find(f => f.code === 'normalized_total').nullable, false);
  assert.equal(model.model.activeVersion, model.model.latestVersion);
  const physicalReview = await call(modelPath + '/review');
  assert.equal(physicalReview.canPublish, false);
  assert.equal(physicalReview.plan.risk, 'SAFE');
  assert.deepEqual(physicalReview.plan.steps, [], 'Inspected physical schema matches the active required-column definition');
  state.after = after; state.finalModel = model; state.finalPhysicalReview = physicalReview;
  await check('APP publication resumes original intent and activates required computed schema');
  if (!state.created) {
    state.createCommand ??= {runtime: {formId: recovery.formId, formVersion: required.version}, state: 'DRAFT',
      idempotencyKey: randomUUID(), values: {subject: 'REQUIRED-COMPUTED-NEW', quantity: '4', rate: '3'}};
    await save(); state.created = await call(records, 'POST', state.createCommand); await save();
  }
  if (!state.deleted) {
    const created = await call(records + '/' + state.created.recordId);
    assert.equal(Number(created.values.total), 13); assert.equal(Number(created.values.normalized_total), 23);
    state.createdDetail = created; await save();
    state.deleteKey ??= randomUUID(); await save();
    await call(records + '/' + state.created.recordId + '?' + new URLSearchParams({formId: recovery.formId,
      formVersion: String(required.version), expectedVersion: String(created.version), idempotencyKey: state.deleteKey}), 'DELETE');
    state.deleted = true; await save();
  }
  state.passed = true; state.finishedAt = new Date().toISOString(); await save();
  await check('new record omits computed values and satisfies physical NOT NULL');
}
