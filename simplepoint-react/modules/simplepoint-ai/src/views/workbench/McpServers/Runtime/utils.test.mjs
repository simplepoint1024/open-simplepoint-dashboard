import assert from 'node:assert/strict';
import test from 'node:test';
import {
  runtimeErrorLabel,
  runtimeNetworkModeLabel,
  runtimeStatusLabel,
} from './runtimeLabels.ts';

const translate = (key) => key;

test('all runtime lifecycle states resolve to distinct translation keys', () => {
  const statuses = [
    'REGISTERING', 'READY', 'DRAINING', 'ERROR', 'OFFLINE',
    'SCALING', 'IDLE', 'DISABLED', 'PENDING', 'ASSIGNED',
    'STARTING', 'RUNNING', 'STOPPING', 'SUCCEEDED', 'FAILED',
    'LOST', 'CANCELLED',
  ];
  assert.equal(new Set(statuses.map((status) => runtimeStatusLabel(
    translate,
    status,
  ))).size, statuses.length);
});

test('unknown runtime values never become user-facing text', () => {
  const rawStatus = 'INTERNAL_RUNTIME_STATE';
  const rawNetworkMode = 'internal-network-driver';
  const status = runtimeStatusLabel(translate, rawStatus);
  const networkMode = runtimeNetworkModeLabel(translate, rawNetworkMode);
  assert.equal(status, 'ai.runtime.status.unknown');
  assert.equal(networkMode, 'ai.runtime.network.unknown');
  assert.equal(status.includes(rawStatus), false);
  assert.equal(networkMode.includes(rawNetworkMode), false);
});

test('runtime diagnostics are finite and unknown values never render', () => {
  const errorCodes = [
    'AI_RUNTIME_NODE_SHUT_DOWN',
    'AI_RUNTIME_NODE_HEARTBEAT_EXPIRED',
    'AI_RUNTIME_WORKLOAD_CANCELLATION_REQUESTED',
    'AI_RUNTIME_PROFILE_REVISION_CHANGED',
    'AI_RUNTIME_POOL_SCALE_DOWN_REQUESTED',
    'AI_RUNTIME_WORKLOAD_DEADLINE_EXPIRED',
    'AI_RUNTIME_WORKLOAD_NOT_OBSERVABLE',
    'AI_RUNTIME_IMAGE_RESOLUTION_FAILED',
    'AI_RUNTIME_OPERATION_FAILED',
  ];
  assert.equal(
    new Set(errorCodes.map((code) => runtimeErrorLabel(translate, code))).size,
    errorCodes.length,
  );
  const privateProse = 'container endpoint and credential details';
  const unknown = runtimeErrorLabel(translate, privateProse);
  assert.equal(unknown, 'ai.runtime.error.unknown');
  assert.equal(unknown.includes(privateProse), false);
});
