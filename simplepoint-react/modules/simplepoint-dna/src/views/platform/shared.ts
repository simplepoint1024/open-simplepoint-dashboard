import {isHttpError, resolveApiErrorMessage} from '@simplepoint/shared/api/client';

export {formatDateTime} from '@simplepoint/shared/format/temporal';

export const resolveErrorMessage = (error: unknown, fallback: string) => {
  if (isHttpError(error)) {
    return resolveApiErrorMessage(error, fallback);
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
};
