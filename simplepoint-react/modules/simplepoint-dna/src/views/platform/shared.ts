import {isHttpError, resolveApiErrorMessage} from '@simplepoint/shared/api/client';

export const formatDateTime = (value?: string | null) => {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
};

export const resolveErrorMessage = (error: unknown, fallback: string) => {
  if (isHttpError(error)) {
    return resolveApiErrorMessage(error, fallback);
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
};
