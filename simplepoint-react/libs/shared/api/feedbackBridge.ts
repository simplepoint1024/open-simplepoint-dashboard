import type {useAppProps} from 'antd/es/app/context';

export type FeedbackBridge = Pick<useAppProps, 'message' | 'modal'>;

type FeedbackRegistration = {
  token: symbol;
  bridge: FeedbackBridge;
};

let activeRegistration: FeedbackRegistration | undefined;
const pendingWaiters = new Set<(bridge: FeedbackBridge) => void>();

/**
 * Makes the Host's context-aware Ant Design feedback APIs available to code
 * that runs outside React. `@simplepoint/shared` is a Module Federation
 * singleton, so host and remote modules resolve the same registration.
 */
export function registerFeedbackBridge(bridge: FeedbackBridge): () => void {
  const token = Symbol('simplepoint-feedback-registration');
  activeRegistration = {token, bridge};
  pendingWaiters.forEach((resolve) => resolve(bridge));
  pendingWaiters.clear();

  return () => {
    if (activeRegistration?.token === token) {
      activeRegistration = undefined;
    }
  };
}

export function getFeedbackBridge(): FeedbackBridge | undefined {
  return activeRegistration?.bridge;
}

/**
 * Waits briefly for the Host registrar during initial application mounting.
 * Standalone consumers get `undefined` after the timeout instead of falling
 * back to Ant Design's context-free static APIs.
 */
export function waitForFeedbackBridge(timeoutMs = 1000): Promise<FeedbackBridge | undefined> {
  const currentBridge = getFeedbackBridge();
  if (currentBridge || timeoutMs <= 0) {
    return Promise.resolve(currentBridge);
  }

  return new Promise((resolve) => {
    const onRegistered = (bridge: FeedbackBridge) => {
      clearTimeout(timeout);
      resolve(bridge);
    };
    const timeout = setTimeout(() => {
      pendingWaiters.delete(onRegistered);
      resolve(undefined);
    }, timeoutMs);
    pendingWaiters.add(onRegistered);
  });
}
