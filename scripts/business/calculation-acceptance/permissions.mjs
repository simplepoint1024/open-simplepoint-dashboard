import assert from 'node:assert/strict';
import {createHash, randomUUID} from 'node:crypto';

/** Changes only the isolated acceptance page's grants; always restores its previous policy. */
export async function verifyCalculationPermissions({call, request, roleId, spec, recordId, submissionId,
  processInstanceId, state, save, progress = console.log}) {
  const field = id => 'field_' + createHash('sha256').update(id).digest('hex');
  const records = spec.runtime + '/records';
  const base = '/common/role-resource-configuration/roles/' + roleId;
  const row = async () => (await call('admin', base + '/resources?page=0&size=100&keyword='
    + encodeURIComponent(spec.resourceCode))).content.find(r => r.code === spec.resourceCode);
  const setPolicy = async policyId => {
    const current = await row(); assert(current?.fieldPermission.supported);
    if (current.fieldPermission.policyId === policyId) return;
    await call('admin', base + '/resource-changes', 'PUT', {changes: [{resourceId: current.id,
      resourceCode: current.code, revision: current.revision, granted: current.granted, fieldPermission: {policyId}}]});
  };
  const pass = async (name, detail = {}) => {state.checks ??= []; state.checks.push({name, ...detail}); await save(); progress('PASS ' + name);};
  if (!state.original) {state.original = await row(); assert(state.original?.granted); await save();}
  const catalog = await call('admin', '/common/field-scopes/page-fields?code=' + encodeURIComponent(spec.resourceCode));
  const main = catalog.models.find(m => m.properties[field('discount_rate')]);
  const child = catalog.models.find(m => m.properties[field('unit_price')]); assert(main && child);
  const derived = ['total_amount', 'tax_total', 'base_amount', 'payable_amount', 'risk_level'];
  const childDerived = ['amount', 'tax_amount', 'net_amount'];
  const plain = await call('admin', records + '/' + recordId);
  async function reject(path, method, body) {
    const response = await request('employee', path, method, body);
    assert([400, 403, 404, 409].includes(response.status), 'Must reject protected field inference or write: ' + path);
    const error = JSON.parse(response.body); assert(error.messageKey, 'Stable public error translation key');
    return {status: response.status, code: error.code, messageKey: error.messageKey};
  }
  function verifyProjection(record, source, access, includeDetails = true) {
    for (const name of derived) assert(!Object.hasOwn(record.values, name), 'Hidden transitive root ' + name);
    for (const line of includeDetails ? record.values.expense_items : []) {
      for (const name of childDerived) assert(!Object.hasOwn(line.values, name), 'Hidden transitive child ' + name);
      if (source === 'child') assert(!Object.hasOwn(line.values, 'unit_price'));
    }
    if (source === 'parent') assert.equal(record.values.discount_rate, access === 'MASKED' ? '***' : undefined);
  }
  try {
    for (const [name, source, access] of [['hidden-child-source', 'child', 'HIDDEN'], ['masked-parent-source', 'parent', 'MASKED']]) {
      state.policies ??= {};
      if (!state.policies[name] || state.deleted?.includes(state.policies[name])) {
        const config = await call('admin', '/common/field-scopes/configuration', 'POST', {
          name: '【计算验收】' + name, description: '临时跨层公式依赖权限验证，完成即恢复并清理',
          entries: [{resource: source === 'child' ? child.model : main.model,
            field: field(source === 'child' ? 'unit_price' : 'discount_rate'), access}],
        }); state.policies[name] = config.id; await save();
      }
      await setPolicy(state.policies[name]);
      // Reuse the same authenticated session: a stale login must not retain the former policy.
      const restricted = await call('employee', records + '/' + recordId); verifyProjection(restricted, source, access);
      const page = await call('employee', records + '/query', 'POST', {page: 0, size: 100});
      verifyProjection(page.records.find(r => r.id === recordId), source, access, false);
      const runtime = await call('employee', spec.runtime), schema = await call('employee', records + '/schema');
      for (const id of derived) {assert(!runtime.document.fields.some(f => f.fieldId === id)); assert(!schema.schema.properties[id]);}
      assert(!JSON.stringify(runtime).includes('ROUND('), 'No raw calculation expression in runtime descriptor');
      const preview = await call('employee', records + '/preview', 'POST', {runtime: {formId: spec.formId, formVersion: spec.version},
        recordId, expectedVersion: restricted.version, state: 'DRAFT', values: {subject: '【计算验收】无副作用权限预览'}});
      verifyProjection({values: {...preview.values, expense_items: preview.details.expense_items.map(values => ({values}))}}, source, access);
      const snapshot = await call('employee', spec.runtime + '/submissions/' + submissionId + '/snapshot');
      verifyProjection(snapshot.record, source, access);
      const workflow = await call('employee', '/app/workflow/instances/' + processInstanceId + '/business-form');
      verifyProjection(workflow.record, source, access);
      const rejected = [];
      for (const id of derived) {
        rejected.push(await reject(records + '/query', 'POST', {page: 0, size: 20,
          filter: {operator: 'GREATER_THAN', fieldId: id, values: ['0'], children: []}}));
        rejected.push(await reject(records + '/query', 'POST', {page: 0, size: 20, sorts: [{fieldId: id, direction: 'DESC'}]}));
      }
      const patch = source === 'parent' ? {discount_rate: '1'} : {expense_items: [{id: plain.values.expense_items[0].id,
        version: plain.values.expense_items[0].version, values: {unit_price: '1'}}]};
      rejected.push(await reject(records + '/' + recordId, 'PUT', {runtime: {formId: spec.formId, formVersion: spec.version},
        expectedVersion: plain.version, state: 'DRAFT', idempotencyKey: randomUUID(), values: patch}));
      assert.deepEqual(await call('admin', records + '/' + recordId), plain, 'Preview and rejected writes leave the aggregate unchanged');
      await pass(name, {recordId, submissionId, processInstanceId, rejected, hiddenRoot: derived, hiddenDetail: childDerived});
    }
    const name = 'masked-calculated-target';
    if (!state.policies[name] || state.deleted?.includes(state.policies[name])) {
      const config = await call('admin', '/common/field-scopes/configuration', 'POST', {
        name: '【计算验收】' + name, description: '计算结果自身脱敏及下游推断保护',
        entries: [{resource: main.model, field: field('total_amount'), access: 'MASKED'}],
      }); state.policies[name] = config.id; await save();
    }
    await setPolicy(state.policies[name]);
    const hidden = ['base_amount', 'payable_amount', 'risk_level'];
    const verify = record => {
      assert.equal(record.values.total_amount, '***');
      for (const id of hidden) assert(!Object.hasOwn(record.values, id));
      assert(Object.hasOwn(record.values, 'tax_total'), 'Independent calculation remains visible');
    };
    const current = await call('employee', records + '/' + recordId); verify(current);
    verify((await call('employee', records + '/query', 'POST', {page: 0, size: 100})).records.find(r => r.id === recordId));
    verify((await call('employee', spec.runtime + '/submissions/' + submissionId + '/snapshot')).record);
    verify((await call('employee', '/app/workflow/instances/' + processInstanceId + '/business-form')).record);
    verify(await call('employee', records + '/preview', 'POST', {runtime: {formId: spec.formId, formVersion: spec.version},
      recordId, expectedVersion: current.version, state: 'DRAFT', values: {subject: '计算列自身脱敏预览'}}));
    const rejected = [];
    for (const id of ['total_amount', ...hidden]) {
      rejected.push(await reject(records + '/query', 'POST', {page: 0, size: 20,
        filter: {operator: 'GREATER_THAN', fieldId: id, values: ['0'], children: []}}));
      rejected.push(await reject(records + '/query', 'POST', {page: 0, size: 20, sorts: [{fieldId: id, direction: 'DESC'}]}));
    }
    assert.deepEqual(await call('admin', records + '/' + recordId), plain);
    await pass(name, {recordId, submissionId, processInstanceId, rejected, hiddenRoot: hidden});
  } finally {
    await setPolicy(state.original.fieldPermission.policyId);
    assert.equal((await row()).fieldPermission.policyId, state.original.fieldPermission.policyId);
    state.restored = true; await save();
    for (const id of Object.values(state.policies ?? {})) {
      if (state.deleted?.includes(id)) continue;
      await call('admin', '/common/field-scopes?ids=' + encodeURIComponent(id), 'DELETE');
      state.deleted ??= []; state.deleted.push(id); await save();
    }
  }
  assert.deepEqual(await call('employee', records + '/' + recordId), plain);
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
}
