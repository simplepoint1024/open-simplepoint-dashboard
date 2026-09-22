import type {
  SimpleTableErrorAction,
  SimpleTableErrorMessageResolver,
} from './types';

/** Only expose bounded validation messages, never SQL/stack details from server failures. */
export function resolveSimpleTableLoadError(error: unknown, fallback: string): string {
  const candidate = error as {status?: number; data?: {code?: string; message?: unknown}} | undefined;
  const message = candidate?.data?.message;
  return candidate?.status === 400 && candidate.data?.code === 'INVALID_QUERY'
    && typeof message === 'string' && message.trim().length > 0 && message.length <= 200
    ? message : fallback;
}

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
