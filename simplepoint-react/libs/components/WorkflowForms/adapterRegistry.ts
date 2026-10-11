import type {WorkflowFormAdapter} from '../WorkflowDesigner/contracts';

type Registry = {
  adapters: Map<string, WorkflowFormAdapter>;
  listeners: Set<() => void>;
  snapshot: readonly WorkflowFormAdapter[];
};

// Each federated application can bundle this module independently. The versioned,
// realm-owned registry makes explicit host registrations visible to every consumer.
const registryKey = Symbol.for('simplepoint.workflow-form-adapters.v1');
const realm = globalThis as typeof globalThis & {[registryKey]?: Registry};
const registry = realm[registryKey] ??= {
  adapters: new Map(), listeners: new Set(), snapshot: Object.freeze([]),
};

function publish() {
  registry.snapshot = Object.freeze([...registry.adapters.values()]);
  registry.listeners.forEach(listener => listener());
}

/** Host-owned registration point. No provider is installed or fetched from saved user-controlled bindings. */
export function registerWorkflowFormAdapter(adapter: WorkflowFormAdapter): () => void {
  if (!/^[A-Za-z][A-Za-z0-9_.-]{0,79}$/.test(adapter.provider)) throw Error('Invalid workflow form provider');
  if (registry.adapters.has(adapter.provider)) throw Error('Workflow form provider already registered');
  // Copy the public entry so caller mutations cannot silently change a published
  // snapshot or make its captured disposer delete another provider's registration.
  const provider = adapter.provider, registered = Object.freeze({...adapter});
  registry.adapters.set(provider, registered);
  publish();
  return () => {
    if (registry.adapters.get(provider) === registered) {
      registry.adapters.delete(provider); publish();
    }
  };
}

export function workflowFormAdapter(provider: string): WorkflowFormAdapter | undefined {
  return registry.adapters.get(provider);
}

export function workflowFormAdapters(): readonly WorkflowFormAdapter[] {
  return registry.snapshot;
}

export function subscribeWorkflowFormAdapters(listener: () => void): () => void {
  registry.listeners.add(listener);
  return () => {registry.listeners.delete(listener);};
}
