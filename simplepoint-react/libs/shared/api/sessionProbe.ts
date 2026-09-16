export type SessionProbeStatus = 'active' | 'inactive' | 'unknown';

type ResponseLike = Pick<Response, 'headers' | 'ok' | 'redirected' | 'status' | 'type' | 'url'>;

const isRedirectStatus = (status: number) => status >= 300 && status < 400;

function responsePathname(response: ResponseLike, origin: string) {
  try {
    return new URL(response.url, origin).pathname;
  } catch {
    return '';
  }
}

function isLoginPath(pathname: string) {
  return pathname === '/login'
    || pathname.startsWith('/oauth2/authorization/')
    || pathname === '/oauth2/authorize';
}

export function isAuthenticationRedirectResponse(
  response: ResponseLike,
  origin = 'http://localhost',
) {
  if (response.type === 'opaqueredirect') return true;
  if (!response.redirected && !isRedirectStatus(response.status)) return false;
  if (isLoginPath(responsePathname(response, origin))) return true;
  return (response.headers.get('content-type') || '').toLowerCase().includes('text/html');
}

export function classifySessionProbeResponse(
  response: ResponseLike,
  origin = 'http://localhost',
): SessionProbeStatus {
  if (
    response.status === 401
    || response.status === 403
    || isAuthenticationRedirectResponse(response, origin)
  ) {
    return 'inactive';
  }
  if (!response.ok) return 'unknown';
  return (response.headers.get('content-type') || '').toLowerCase().includes('text/html')
    ? 'inactive'
    : 'active';
}
