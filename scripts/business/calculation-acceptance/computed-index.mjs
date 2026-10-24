import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';

/** Publish an index on a stable computed field through APP/DNA, then inspect physical drift. */
export async function verifyComputedIndex({call, request, appId, spec, modelId, state, save}) {
  const base = `/app/applications/${appId}/forms/${spec.formId}`;
  const query = body => call(spec.runtime + '/records/query', 'POST', body);
  state.before ??= await query({page: 0, size: 100, sorts: [{fieldId: 'subject', direction: 'ASC'}]});
  assert.equal(state.before.total, 65); await save();
  if (!state.version) {
    const draft = await call(base + '/draft');
    assert(draft.document.fields.find(field => field.fieldId === 'total')?.calculation);
    const document = structuredClone(draft.document);
    assert(!document.indexes.some(index => index.indexId === 'calculated_total_index'));
    document.indexes.push({indexId: 'calculated_total_index', storageCode: 'calculated_total_index', unique: false, fieldIds: ['total']});
    state.originalDocument = draft.document; state.key = randomUUID(); await save();
    const changed = await call(base + '/draft', 'PUT', {expectedRevision: draft.revision, document});
    state.version = (await call(base + '/versions', 'POST', {expectedRevision: changed.revision})).version; await save();
  }
  const modelPath = '/dna/platform/dna/runtime/models/' + modelId;
  for (let i = 0; i < 150 && !state.active; i++) {
    const response = await request(base + `/versions/${state.version}/publication`, 'POST', {pageId: spec.pageId, idempotencyKey: state.key});
    if (response.status === 409) {
      const error = JSON.parse(response.body); assert.equal(error.code, 'PUBLICATION_REVIEW_REQUIRED');
      const review = await call(modelPath + '/review'); assert(review.canPublish);
      assert(review.plan.steps.every(step => JSON.stringify(step).includes('INDEX')), 'Only an index migration is authorized here');
      state.review = review; await save();
      state.migration = await call(modelPath + '/publish', 'POST', {targetVersion: review.plan.targetVersion,
        expectedVersion: review.expectedVersion, actualChecksum: review.plan.actualChecksum, targetChecksum: review.plan.targetChecksum});
      assert.equal(state.migration.status, 'ACTIVE'); await save();
    } else {
      assert.equal(response.status, 200, response.body);
      const payload = JSON.parse(response.body), result = Object.hasOwn(payload, 'data') ? payload.data : payload;
      assert(!result.inspection?.issues?.length);
      state.publication = result; state.active = result.pageBinding?.status === 'ACTIVE'; await save();
    }
    if (!state.active) await delay(200);
  }
  assert(state.active);
  const model = await call(modelPath), schema = JSON.parse(model.schemaJson);
  assert(schema.indexes.some(index => index.code === 'calculated_total_index'));
  const physical = await call(modelPath + '/review'); assert.deepEqual(physical.plan.steps, []);
  const after = await query({page: 0, size: 100, sorts: [{fieldId: 'subject', direction: 'ASC'}]});
  assert.deepEqual(after.records.map(row => ({id: row.id, values: row.values})),
    state.before.records.map(row => ({id: row.id, values: row.values})));
  const filter = {operator: 'GREATER_THAN_OR_EQUAL', fieldId: 'total', values: ['10'], children: []};
  const sorts = [{fieldId: 'total', direction: 'DESC'}];
  const all = await query({page: 0, size: 100, filter, sorts}); assert(all.total > 7);
  const ids = [];
  for (let page = 0; page < Math.ceil(all.total / 7); page++) {
    const part = await query({page, size: 7, filter, sorts}); assert.equal(part.total, all.total);
    ids.push(...part.records.map(row => row.id));
  }
  assert.deepEqual(ids, all.records.map(row => row.id)); assert.equal(new Set(ids).size, all.total);
  for (let i = 0; i < all.records.length; i++) {
    assert(Number(all.records[i].values.total) >= 10);
    if (i) assert(Number(all.records[i - 1].values.total) >= Number(all.records[i].values.total));
  }
  Object.assign(state, {passed: true, schema, physical, matched: all.total, ids, completedAt: new Date().toISOString()});
  await save(); console.log('PASS physical computed-field index, unchanged values and stable server filter/sort/pagination');
}
