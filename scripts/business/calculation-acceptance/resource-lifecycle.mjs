import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';

/** Uses only lifecycle and authorization APIs; never edits another service's persistence. */
export async function verifyCalculationResourceLifecycle({call, request, appId, pageId, formId, formVersion,
  employeeRoleId, readScopeId, state, save, exerciseTransportGap}) {
  const runtime = `/app/runtime/applications/${appId}/pages/${pageId}`;
  const base = `/app/applications/${appId}/pages/${pageId}/resource-lifecycle`;
  const form = `/app/applications/${appId}/forms/${formId}`;
  const check = async (name, detail = {}) => {state.checks ??= []; state.checks.push({name, ...detail}); await save(); console.log('PASS ' + name);};
  const granted = () => call('admin', '/common/roles/authorized?roleId=' + encodeURIComponent(employeeRoleId));
  const query = actor => call(actor, runtime + '/records/query', 'POST', {page: 0, size: 100,
    sorts: [{fieldId: 'subject', direction: 'ASC'}]});
  if (state.passed) return;
  if (!state.initial) {
    const descriptor = await call('admin', runtime); state.resourceCode = descriptor.resourceCode;
    state.originalGrants = await granted(); assert(!state.originalGrants.includes(state.resourceCode), 'Use an isolated ungranted page');
    state.before = await query('admin'); state.initial = await call('admin', base);
    assert.equal(state.initial.state, 'ONLINE'); assert.equal(state.initial.unfinishedSubmissions, 0); await save();
  }
  if (!state.verifiedInitialGrant) {
    await call('admin', '/common/roles/authorize', 'POST', {roleId: employeeRoleId, resourceCodes: [state.resourceCode], dataScopeId: readScopeId});
    assert.deepEqual(await query('employee'), state.before); state.verifiedInitialGrant = true; await save();
  }
  state.offlineCommand ??= {operationId: randomUUID(), expectedGeneration: state.initial.generation}; await save();
  if (!state.offline) {
    const accept = async () => {
      const first = await call('admin', base + '/offline', 'POST', state.offlineCommand);
      const retry = await call('admin', base + '/offline', 'POST', state.offlineCommand);
      assert.equal(first.operationId, retry.operationId);
      return first;
    };
    if (exerciseTransportGap) await exerciseTransportGap(accept, () => call('admin', base));
    else await accept();
    for (let i = 0; i < 180; i++) {
      const current = await call('admin', base);
      if (current.state === 'OFFLINE') {state.offline = current; await save(); break;}
      await delay(1000);
    }
    assert(state.offline, 'All required durable participants must confirm offline');
    assert.deepEqual([...state.offline.completedParticipants].sort(), [...state.offline.requiredParticipants].sort());
    assert(state.offline.requiredParticipants.length >= 2);
    const denied = await request('employee', runtime + '/records/query', 'POST', {page: 0, size: 10});
    assert([403, 404, 409].includes(denied.status)); assert(!(await granted()).includes(state.resourceCode));
    await check('durable offline completes all participants and revokes cached ordinary grants', {operation: state.offline, deniedStatus: denied.status});
  }
  state.onlineKey ??= randomUUID(); await save();
  if (!state.online) {
    for (let i = 0; i < 150; i++) {
      const result = await call('admin', form + `/versions/${formVersion}/publication`, 'POST', {pageId, idempotencyKey: state.onlineKey});
      if (result.pageBinding?.status === 'ACTIVE') {state.online = result; await save(); break;}
      await delay(150);
    }
    assert(state.online);
    const now = await call('admin', base); assert.equal(now.state, 'ONLINE'); assert(now.generation > state.initial.generation);
    assert.deepEqual(await query('admin'), state.before, 'Resource lifecycle preserves stored values and audit history');
    assert(!(await granted()).includes(state.resourceCode), 'Re-online cannot revive a revoked grant');
    const denied = await request('employee', runtime); assert([403, 404].includes(denied.status));
    const stale = await request('admin', base + '/offline', 'POST', {operationId: randomUUID(), expectedGeneration: state.initial.generation});
    assert.equal(stale.status, 409);
    const repeatedOld = await call('admin', base + '/offline', 'POST', state.offlineCommand);
    assert.equal(repeatedOld.operationId, state.offlineCommand.operationId);
    assert.equal((await call('admin', base)).state, 'ONLINE', 'Old operation replay must not offline a new generation');
    assert(state.originalGrants.every(code => (code !== state.resourceCode)), 'Prior grants were disjoint');
    const after = await granted(); assert(state.originalGrants.every(code => after.includes(code)), 'Unrelated role grants must remain');
    await check('re-online preserves data, fences the old generation and requires a new explicit grant', {generation: now.generation, deniedStatus: denied.status});
  }
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
}
