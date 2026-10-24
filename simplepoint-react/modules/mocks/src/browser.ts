import { setupWorker } from 'msw/browser';
import { http, HttpResponse } from 'msw';
import { mockHandlers } from './registry';

export const worker = setupWorker(
  http.get('/csrf', () => HttpResponse.json({
    token: 'mock-session-csrf', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf',
  })),
  ...mockHandlers,
);

export function startMockWorker() {
  return worker.start({
    onUnhandledRequest: 'bypass',
  });
}
