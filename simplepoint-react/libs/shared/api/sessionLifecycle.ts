// Shared through the browser realm because federated modules can bundle separate copies.
// This is a navigation fence, never an authorization decision. A full navigation resets it.
const EXIT_EVENT = 'sp-session-exit';
type SessionWindow = Window & {__simplepointSessionExiting?: boolean};

export function isSessionExiting(): boolean {
  return typeof window !== 'undefined' && (window as SessionWindow).__simplepointSessionExiting === true;
}

export function beginSessionExit() {
  if (typeof window === 'undefined' || isSessionExiting()) return;
  (window as SessionWindow).__simplepointSessionExiting = true;
  window.dispatchEvent(new Event(EXIT_EVENT));
}

export function subscribeSessionExit(listener: () => void): () => void {
  if (typeof window === 'undefined') return () => {};
  const target = window;
  target.addEventListener(EXIT_EVENT, listener);
  return () => target.removeEventListener(EXIT_EVENT, listener);
}
