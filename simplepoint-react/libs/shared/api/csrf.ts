type CsrfToken = {token: string; headerName: string; parameterName: string};
let inFlight: Promise<CsrfToken> | undefined;

/** Share concurrent probes, but never cache a token across session changes or retry writes. */
export function getCsrfToken(): Promise<CsrfToken> {
  if (!inFlight) {
    inFlight = fetch('/csrf', {
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      headers: {Accept: 'application/json'},
      signal: AbortSignal.timeout(5000),
    }).then(async response => {
      if (!response.ok) throw new Error('Session protection unavailable');
      const value = await response.json() as CsrfToken;
      if (typeof value.token !== 'string' || !value.token
        || value.headerName !== 'X-CSRF-TOKEN' || value.parameterName !== '_csrf') {
        throw new Error('Invalid session protection response');
      }
      return value;
    }).finally(() => { inFlight = undefined; });
  }
  return inFlight;
}

export async function addCsrfHeader(url: string, method: string, headers: Headers): Promise<void> {
  if (['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method.toUpperCase())
    || typeof window === 'undefined') return;
  const target = new URL(url, window.location.href);
  // Never disclose a session token to a different origin.
  if (target.origin !== window.location.origin) return;
  const token = await getCsrfToken();
  headers.set(token.headerName, token.token);
}
