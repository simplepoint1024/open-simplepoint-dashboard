import type {
  SimpleTableErrorAction,
  SimpleTableErrorMessageResolver,
} from './types';

export function resolveSimpleTableOperationError(
  error: unknown,
  action: SimpleTableErrorAction,
  resolver: SimpleTableErrorMessageResolver | undefined,
  fallback: () => string,
): string {
  if (resolver) {
    try {
      const resolved = resolver(error, action)?.trim();
      if (resolved) return resolved;
    } catch {
      // A page-specific presentation hook must never hide the safe fallback.
    }
  }
  return fallback();
}
