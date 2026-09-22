/** Locks only the same action, synchronously (before React updates loading props). */
export function createActionRunner() {
  const running = new Set<string>();
  return async <T>(key: string, action: () => Promise<T>, onError: (error: unknown) => void) => {
    if (running.has(key)) return {status: 'busy' as const};
    running.add(key);
    try {
      return {status: 'success' as const, value: await action()};
    } catch (error) {
      onError(error);
      return {status: 'failed' as const};
    } finally {
      running.delete(key);
    }
  };
}

export function isFormValidationError(error: unknown): boolean {
  return typeof error === 'object' && error !== null && 'errorFields' in error
    && Array.isArray(error.errorFields) && error.errorFields.length > 0;
}
