type RequestSlot = {current: AbortController | undefined};

export function operationIsCurrent(capturedScope: string, currentScope: string, capturedOwner: string | undefined,
  currentOwner: string | undefined, alive: boolean, signal?: AbortSignal): boolean {
  return alive && capturedScope === currentScope && capturedOwner === currentOwner && !signal?.aborted;
}

/** Synchronous ownership acquisition, independent of React's next render. */
export function acquireOperation(slot: RequestSlot): AbortController | undefined {
  if (slot.current) return undefined;
  const request = new AbortController(); slot.current = request; return request;
}

/** A late old-scope completion can never release a newer owner's request. */
export function releaseOperation(slot: RequestSlot, request: AbortController): boolean {
  if (slot.current !== request) return false;
  slot.current = undefined; return true;
}

export function resetOperation(slot: RequestSlot): void {
  slot.current?.abort(); slot.current = undefined;
}
