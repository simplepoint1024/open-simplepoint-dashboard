import {useEffect, useState} from 'react';
import {hostEntry} from './releaseUpdate';

/** Low-frequency same-origin check; never reloads automatically or follows login redirects. */
export function useReleaseUpdate() {
  const [available, setAvailable] = useState(false);
  useEffect(() => {
    const initial = hostEntry([...document.scripts].map(script => script.src));
    if (!initial) return;
    const controller = new AbortController();
    let inFlight = false;
    let lastCheck = Date.now();
    const check = async () => {
      if (document.hidden || inFlight || Date.now() - lastCheck < 300_000) return;
      lastCheck = Date.now();
      inFlight = true;
      try {
        const response = await fetch('/', {credentials: 'same-origin', cache: 'no-store', redirect: 'error',
          signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10_000)])});
        if (!response.ok || !response.headers.get('content-type')?.includes('text/html')) return;
        const html = new DOMParser().parseFromString(await response.text(), 'text/html');
        const entry = hostEntry([...html.querySelectorAll('script[src]')].map(script => script.getAttribute('src') ?? ''));
        if (entry && entry !== initial && !controller.signal.aborted) setAvailable(true);
      } catch {
        // Offline/expired sessions do not constitute a release and never interrupt editing.
      } finally { inFlight = false; }
    };
    const timer = window.setInterval(() => void check(), 300_000);
    document.addEventListener('visibilitychange', check);
    return () => { controller.abort(); clearInterval(timer); document.removeEventListener('visibilitychange', check); };
  }, []);
  return available;
}
