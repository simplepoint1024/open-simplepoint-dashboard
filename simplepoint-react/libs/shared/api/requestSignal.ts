/** Request timeout covers receiving headers. Caller cancellation must remain
 * connected while the response body is consumed (especially an SSE stream).
 */
export function createRequestSignal(externalSignal?: AbortSignal, timeoutMs = 0) {
  if (timeoutMs <= 0) {
    return {signal: externalSignal, cleanup: () => {}, isTimeout: () => false};
  }

  const timeoutController = new AbortController();
  const signal = externalSignal
    ? AbortSignal.any([externalSignal, timeoutController.signal])
    : timeoutController.signal;
  let timedOut = false;
  const timeoutId = setTimeout(() => {
    if (signal.aborted) return;
    timedOut = true;
    timeoutController.abort(new DOMException('Request timeout', 'TimeoutError'));
  }, timeoutMs);

  return {
    signal,
    cleanup: () => clearTimeout(timeoutId),
    isTimeout: () => timedOut,
  };
}
