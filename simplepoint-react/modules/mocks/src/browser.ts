import { setupWorker } from 'msw/browser';
import { mockHandlers } from './registry';

export const worker = setupWorker(...mockHandlers);

export function startMockWorker() {
  return worker.start({
    onUnhandledRequest: 'bypass',
  });
}
