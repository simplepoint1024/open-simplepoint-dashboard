import {useEffect, useRef} from 'react';
import {navigationMetrics} from '@/utils/navigationMetrics';

/** Measures committed route activation to ready paint; not a substitute for field Web Vitals. */
export function useNavigationMeasurement(active: boolean) {
  const element = useRef<HTMLDivElement>(null);
  const visited = useRef(false);
  useEffect(() => {
    const panel = element.current;
    if (!active || !panel) return;
    const started = performance.now();
    const cached = visited.current;
    visited.current = true;
    let completed = false;
    let frame = 0;
    const pending = () => !panel.childElementCount || !!panel.querySelector('.sp-loading-placeholder, [aria-busy="true"]');
    const record = (outcome: 'ready' | 'error' | 'interrupted') => {
      const ended = performance.now();
      const requests = (performance.getEntriesByType('resource') as PerformanceResourceTiming[])
        .filter(entry => entry.startTime >= started && entry.responseEnd <= ended);
      navigationMetrics.record({durationMs: Math.round(ended - started), cached, outcome,
        requestCount: requests.length, transferBytes: requests.reduce((bytes, entry) => bytes + entry.transferSize, 0)});
    };
    const check = () => {
      if (completed || frame || pending()) return;
      frame = requestAnimationFrame(() => {
        frame = requestAnimationFrame(() => {
          frame = 0;
          if (pending()) return;
          completed = true;
          observer.disconnect();
          record(panel.querySelector('.ant-result-error, .ant-alert-error') ? 'error' : 'ready');
        });
      });
    };
    const observer = new MutationObserver(check);
    observer.observe(panel, {childList: true, subtree: true, attributes: true, attributeFilter: ['aria-busy']});
    check();
    return () => {
      observer.disconnect();
      cancelAnimationFrame(frame);
      if (!completed) record('interrupted');
    };
  }, [active]);
  return element;
}
