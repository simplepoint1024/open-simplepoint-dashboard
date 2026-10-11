import {useSyncExternalStore} from 'react';
import {subscribeWorkflowFormAdapters, workflowFormAdapters} from './adapterRegistry';

/** React consumers also observe late registration, replacement and disposal. */
export function useWorkflowFormAdapters() {
  return useSyncExternalStore(subscribeWorkflowFormAdapters, workflowFormAdapters, workflowFormAdapters);
}
