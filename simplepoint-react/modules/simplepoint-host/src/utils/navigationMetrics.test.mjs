import assert from 'node:assert/strict';
import test from 'node:test';
import {createNavigationMetrics} from './navigationMetrics.ts';

test('diagnostics are bounded and returned snapshots cannot mutate history', () => {
  const metrics = createNavigationMetrics(2);
  const sample = {durationMs: 10, cached: false, outcome: 'ready', requestCount: 2, transferBytes: 100};
  for (let i = 0; i < 3; i++) metrics.record(sample);
  assert.deepEqual(metrics.snapshot().map(item => item.sequence), [2, 3]);
  metrics.snapshot()[0].durationMs = 999;
  assert.equal(metrics.snapshot()[0].durationMs, 10);
  metrics.record({...sample, durationMs: NaN});
  assert.equal(metrics.snapshot().length, 2);
});
