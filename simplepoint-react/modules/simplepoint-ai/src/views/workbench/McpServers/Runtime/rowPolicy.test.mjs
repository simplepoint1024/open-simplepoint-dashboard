import test from 'node:test';
import assert from 'node:assert/strict';
import {canEditPool, canScalePool} from './rowPolicy.ts';

const row = {id: 'p1', code: 'pool', name: 'Pool', serverId: 's1', imageReference: 'image', imageDigest: 'digest', networkMode: 'none',
  requestedMemoryBytes: 256, requestedNanoCpus: 500, requestedPidsLimit: 128, minReplicas: 0, maxReplicas: 5,
  desiredReplicas: 1, activationReplicas: 1, prewarmNodes: 1, idleTimeoutSeconds: 30, replicaLifetimeSeconds: 300,
  secretIds: [], egressAllowlist: []};
test('runtime pool forms cannot overwrite masked, hidden or malformed desired state', () => {
  assert.equal(canEditPool(row), true);
  for (const field of Object.keys(row)) {
    assert.equal(canEditPool({...row, [field]: '***'}), false, field);
    const hidden = {...row}; delete hidden[field];
    assert.equal(canEditPool(hidden), false, field);
  }
  assert.equal(canEditPool({...row, requestedNanoCpus: Number.NaN}), false);
  assert.equal(canEditPool({...row, secretIds: ['***']}), false);
  assert.equal(canScalePool(row), true);
  for (const field of ['minReplicas', 'maxReplicas', 'desiredReplicas']) assert.equal(canScalePool({...row, [field]: '***'}), false);
});
