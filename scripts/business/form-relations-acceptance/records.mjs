import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
const identity = spec => ({formId: spec.formId, formVersion: spec.version});
const command = (spec, values, extra = {}) => ({runtime: identity(spec), state: 'DRAFT', idempotencyKey: randomUUID(), values, ...extra});
const ref = (spec, relation, suffix = '') => `${spec.runtime}/references/${relation}${suffix}`;
const params = spec => new URLSearchParams(identity(spec));
const intent = (observation, applyDefaultMappingIds = []) => ({contractVersion: '1',
  confirmations: [{relationId: 'customer', observation, applyDefaultMappingIds}]});

/** Public API acceptance against real runtime, immutable policies and physical constraints. */
export async function verifyRelationRecords({call, request, state, save, topology}) {
  const forms = state.forms, customer = forms.rel_customer, product = forms.rel_product, trade = forms.rel_trade;
  const employee = forms.rel_employee, profile = forms.rel_profile;
  state.samples ??= {}; const samples = state.samples;
  const runtime = spec => spec.runtime + '/records';
  async function create(key, spec, values, extra = {}) {
    if (!samples[key]) {
      const cmd = state.pendingCreates?.[key] ?? command(spec, values, extra);
      state.pendingCreates ??= {}; state.pendingCreates[key] = cmd; await save();
      const result = await call(runtime(spec), 'POST', cmd);
      samples[key] = result.currentRecord ?? await call(runtime(spec) + '/' + result.recordId);
      delete state.pendingCreates[key]; await save();
    }
    return samples[key];
  }
  const passed = async (name, detail = {}) => {
    state.checks ??= {}; state.checks[topology] ??= {};
    state.checks[topology][name] = {passedAt: new Date().toISOString(), ...detail}; await save(); console.log('PASS ' + topology + ' ' + name);
  };
  const reject = async (path, method, body) => {
    const r = await request(path, method, body);
    assert([400, 403, 404, 409].includes(r.status), 'Expected domain rejection: ' + path + ' ' + r.status);
    const e = JSON.parse(r.body); assert(e.messageKey, 'Namespaced localized domain envelope');
    return {status: r.status, code: e.code, messageKey: e.messageKey};
  };
  async function remove(spec, record, base = runtime(spec), query = '') {
    const response = await request(base + '/' + record.id + '?' + (query ? query + '&' : '') + new URLSearchParams({expectedVersion: record.version,
      ...identity(spec), idempotencyKey: randomUUID()}), 'DELETE');
    assert([200, 204].includes(response.status), 'Expected successful record deletion');
  }
  const seeded = await create('customer_alpha', customer, {name: '华东实验室・漢字😀', address: '上海合成路 101 号', department: 'A'});
  const alpha = await call(`${runtime(customer)}/${seeded.id}`);
  const beta = await create('customer_beta', customer, {name: '华北实验室', address: '北京合成路 202 号', department: 'B'});
  const good = await create('product', product, {name: '合成测试商品', price: '12.34'});
  const person = await create(topology + '_race_employee', employee, {name: '合成测试并发员工', department: 'A'});
  for (let i = 0; i < 61; i++) await create('customer_page_' + i, customer, {name: '分页客户 ' + String(i).padStart(3, '0'), address: '合成路 ' + i, department: i % 2 ? 'A' : 'B'});
  const candidates = await call(ref(trade, 'customer') + '?' + params(trade));
  const next = await call(ref(trade, 'customer') + '?' + params(trade) + '&page=1&size=50');
  assert(candidates.total >= 63); assert.equal(candidates.records.length, 50); assert(next.records.length >= 13);
  assert.equal(new Set([...candidates.records, ...next.records].map(x => x.id)).size, candidates.records.length + next.records.length);
  const search = await call(ref(trade, 'customer') + '?' + params(trade) + '&keyword=' + encodeURIComponent('漢字😀'));
  assert(search.records.some(x => x.id === alpha.id));
  const resolved = await call(ref(trade, 'customer', '/resolve') + '?' + params(trade), 'POST', [alpha.id]);
  assert(resolved.some(x => x.id === alpha.id && x.label.includes('漢字😀')));
  await passed('candidate-search-pagination-resolution', {total: candidates.total, firstPage: candidates.records.length, secondPage: next.records.length});

  const observe = (target, applyDefaultMappingIds = [], record) => call(ref(trade, 'customer', '/mapping'), 'POST', {
    runtime: identity(trade), targetRecordId: target?.id ?? null, applyDefaultMappingIds,
    ...(record ? {recordId: record.id, expectedVersion: record.version} : {})});
  const observation = await observe(alpha);
  const main = await create(topology + '_trade', trade, {name: '客户交易与四十行商品', customer: alpha.id,
    default_address: '用户确认的送货地址', items: Array.from({length: 40}, (_, i) => ({values: {product: good.id, quantity: String(i + 1)}}))},
    {referenceMappings: intent(observation.observation)});
  assert.equal(main.values.customer, alpha.id); assert.equal(main.values.items.length, 40);
  assert(main.values.items.every(row => row.values.product === good.id));
  assert.equal(main.values.snapshot_address, alpha.values.address);
  assert.equal(main.values.default_address, '用户确认的送货地址'); assert(!Object.hasOwn(main.values, 'live_address'));
  const live = await call(ref(trade, 'customer', '/live'), 'POST', {runtime: identity(trade), targetRecordId: alpha.id});
  assert.equal(live.values.live_address.value, alpha.values.address);
  const drill = await call(ref(trade, 'customer', '/' + alpha.id) + '?' + params(trade));
  assert.equal(drill.record.id, alpha.id); assert(drill.form);
  await passed('root-detail-mapping-live-and-drill', {recordId: main.id, detailRows: main.values.items.length});

  const invalidTrade = `无效明细事务 ${randomUUID()}`;
  const beforeFailure = await call(runtime(trade) + '/query', 'POST', {page: 0, size: 50});
  const invalidDetail = await reject(runtime(trade), 'POST', command(trade, {
    name: invalidTrade, customer: alpha.id,
    items: Array.from({length: 40}, (_, i) => ({values: {product: i === 39 ? randomUUID() : good.id, quantity: '1'}})),
  }, {referenceMappings: intent(observation.observation)}));
  const afterFailure = await call(runtime(trade) + '/query', 'POST', {page: 0, size: 50});
  assert.equal(afterFailure.total, beforeFailure.total);
  assert(!afterFailure.records.some(record => record.values.name === invalidTrade));
  await passed('invalid-detail-rejects-whole-aggregate', {...invalidDetail, rows: 40});

  const bad = await reject(runtime(trade), 'POST', command(trade, {name: '不允许伪造快照', customer: alpha.id, snapshot_address: '伪造'},
    {referenceMappings: intent(observation.observation)}));
  await passed('snapshot-tamper-rejected', bad);
  const defaultObservation = await observe(alpha, ['default_address']);
  const mismatchedDefault = command(trade, {name: '默认值确认冲突', customer: alpha.id, default_address: '用户修改后的地址'},
    {referenceMappings: intent(defaultObservation.observation, ['default_address'])});
  const defaultRejection = await reject(runtime(trade), 'POST', mismatchedDefault);
  assert.equal(defaultRejection.status, 409);
  assert.equal(defaultRejection.code, 'RECORD_REFERENCE_CONFLICT');
  assert.deepEqual(await reject(runtime(trade), 'POST', mismatchedDefault), defaultRejection);
  await passed('conflicting-default-and-replay-rejected', defaultRejection);
  const protectedDelete = await reject(runtime(customer) + '/' + alpha.id + '?' + new URLSearchParams({expectedVersion: alpha.version,
    ...identity(customer), idempotencyKey: randomUUID()}), 'DELETE');
  assert.equal(protectedDelete.code, 'RECORD_REFERENCE_CONFLICT');
  await passed('referenced-target-delete-restricted', protectedDelete);

  const parentQuery = new URLSearchParams({parentFormId: customer.formId, parentFormVersion: customer.version}).toString();
  const inverse = `${customer.runtime}/records/${alpha.id}/relations/trades/records`;
  const at = suffix => inverse + suffix + '?' + parentQuery;
  const invRuntime = await call(at('/runtime')); assert(invRuntime.source);
  const list = await call(at('/query'), 'POST', {page: 0, size: 50}); assert(list.records.some(r => r.id === main.id));
  state.inverseCreates ??= {}; const key = topology + '_inverse';
  if (state.inverseCreates[key]) {
    const previous = await request(`${runtime(trade)}/${state.inverseCreates[key]}`);
    assert([200, 404].includes(previous.status));
    if (previous.status === 404) {
      delete state.inverseCreates[key]; await save();
    } else {
      const envelope = JSON.parse(previous.body), record = envelope.data ?? envelope;
      if (record.values.customer == null) {
        await remove(trade, record);
        delete state.inverseCreates[key]; await save();
      }
    }
  }
  if (!state.inverseCreates[key]) {
    const created = await call(at(''), 'POST', command(trade, {name: topology + ' 客户内新建交易'},
      {referenceMappings: intent(defaultObservation.observation, ['default_address'])}));
    state.inverseCreates[key] = created.recordId; await save();
  }
  const own = await call(at('/' + state.inverseCreates[key]));
  assert.equal(own.values.customer, alpha.id);
  assert.equal(own.values.default_address, alpha.values.address);
  assert.equal(own.values.snapshot_address, alpha.values.address);
  const changed = await call(at('/' + own.id), 'PUT', command(trade, {name: topology + ' 客户内编辑交易'}, {expectedVersion: own.version}));
  const fresh = changed.currentRecord ?? await call(at('/' + own.id));
  assert.equal(fresh.values.customer, alpha.id);
  const rebind = await reject(at('/' + own.id), 'PUT', command(trade, {customer: beta.id}, {expectedVersion: fresh.version}));
  const clear = await observe(null, [], fresh);
  await call(at('/' + own.id + '/unlink'), 'POST', command(trade, {customer: null},
    {expectedVersion: fresh.version, referenceMappings: intent(clear.observation)}));
  const unlinked = await call(runtime(trade) + '/' + own.id); assert.equal(unlinked.values.customer, null);
  assert.equal(unlinked.values.snapshot_address, null);
  assert.equal(unlinked.values.default_address, alpha.values.address);
  await reject(at('/' + own.id), 'GET');
  await remove(trade, unlinked); delete state.inverseCreates[key]; await save();
  const removalName = topology + ' 客户内删除交易';
  const savedForRemoval = list.records.find(record => record.values.name === removalName);
  const removableId = savedForRemoval?.id ?? (await call(at(''), 'POST', command(trade, {name: removalName},
    {referenceMappings: intent(observation.observation)}))).recordId;
  const related = await call(at('/' + removableId));
  await remove(trade, related, inverse, parentQuery);
  assert.equal((await reject(`${runtime(trade)}/${related.id}`, 'GET')).status, 404);
  assert.equal((await call(`${runtime(customer)}/${alpha.id}`)).id, alpha.id);
  await passed('inverse-crud-fixed-parent-and-unlink', {rebind, scopedDelete: true});

  const betaInverse = `${customer.runtime}/records/${beta.id}/relations/trades/records`;
  const inversePage = base => call(`${base}/query?${parentQuery}`, 'POST', {page: 0, size: 50});
  const movable = await call(at(''), 'POST', command(trade, {name: `${topology} 改绑计数验收`},
    {referenceMappings: intent(observation.observation)}));
  const movableRoute = `${runtime(trade)}/${movable.recordId}`;
  try {
    const previous = await call(movableRoute);
    const [oldBefore, newBefore] = await Promise.all([inversePage(inverse), inversePage(betaInverse)]);
    const confirmed = await observe(beta, [], previous);
    await call(movableRoute, 'PUT', command(trade, {customer: beta.id},
      {expectedVersion: previous.version, referenceMappings: intent(confirmed.observation)}));
    const [oldAfter, newAfter] = await Promise.all([inversePage(inverse), inversePage(betaInverse)]);
    assert.equal(oldAfter.total, oldBefore.total - 1);
    assert.equal(newAfter.total, newBefore.total + 1);
    assert(!oldAfter.records.some(record => record.id === previous.id));
    assert(newAfter.records.some(record => record.id === previous.id));
  } finally {
    await remove(trade, await call(movableRoute));
  }
  await passed('rebind-updates-both-inverse-counts', {oldParentDifference: -1, newParentDifference: 1});

  const uniquenessCheck = 'one-to-one-unique-and-soft-delete-retention';
  if (!state.checks[topology][uniquenessCheck]) {
    state.profileRaces ??= {};
    const race = state.profileRaces[topology] ??= {commands: ['A', 'B'].map(label =>
      command(profile, {name: `${topology} 一对一档案 ${label}`, employee: person.id}))};
    await save();
    if (!race.winner) {
      const responses = await Promise.all(race.commands.map(body => request(runtime(profile), 'POST', body)));
      assert.deepEqual(responses.map(result => result.status).sort(), [200, 409],
        responses.filter(result => result.status !== 200).map(result => result.body.slice(0, 270)).join('\n'));
      const winner = responses.findIndex(result => result.status === 200);
      const accepted = await call(runtime(profile), 'POST', race.commands[winner]);
      race.winner = accepted.currentRecord ?? await call(`${runtime(profile)}/${accepted.recordId}`);
      assert.equal(race.winner.values.employee, person.id);
      race.rejection = JSON.parse(responses[1 - winner].body).code; await save();
      assert.equal((await reject(runtime(profile), 'POST', race.commands[1 - winner])).code, race.rejection);
    }
    race.deleteKey ??= randomUUID(); await save();
    const deleteQuery = new URLSearchParams({...identity(profile), expectedVersion: race.winner.version, idempotencyKey: race.deleteKey});
    const deleted = await request(`${runtime(profile)}/${race.winner.id}?${deleteQuery}`, 'DELETE');
    assert([200, 204].includes(deleted.status), 'Expected the winning profile to be deleted');
    const afterSoftDelete = await reject(runtime(profile), 'POST', command(profile, {name: '软删不能释放引用', employee: person.id}));
    await passed(uniquenessCheck, {concurrentResults: [200, 409], rejection: race.rejection, afterSoftDelete});
  }
}
