import {HttpError} from '@simplepoint/shared/api/client';

export const formatDateTime = (value?: string | null) => {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
};

export const resolveErrorMessage = (error: unknown, fallback: string) => {
  if (error instanceof HttpError && error.body?.trim()) {
    return error.body.trim();
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
};
