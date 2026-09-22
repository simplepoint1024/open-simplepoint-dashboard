export type NavigationSample = {
  durationMs: number;
  cached: boolean;
  outcome: 'ready' | 'error' | 'interrupted';
  requestCount: number;
  transferBytes: number;
};

/** Bounded, ephemeral diagnostics: no URLs, query parameters, account data or response bodies. */
export function createNavigationMetrics(capacity = 60) {
  const samples: Array<NavigationSample & {sequence: number}> = [];
  let sequence = 0;
  return {
    record(sample: NavigationSample) {
      if (!Number.isFinite(sample.durationMs) || sample.durationMs < 0) return;
      samples.push({...sample, sequence: ++sequence});
      if (samples.length > capacity) samples.splice(0, samples.length - capacity);
    },
    snapshot: () => samples.map(sample => ({...sample})),
  };
}

export const navigationMetrics = createNavigationMetrics();
if (typeof window !== 'undefined') {
  Object.defineProperty(window, 'spPerformance', {
    configurable: true,
    value: Object.freeze({snapshot: navigationMetrics.snapshot}),
  });
}
