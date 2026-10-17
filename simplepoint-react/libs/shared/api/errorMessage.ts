import type {TranslateFn} from '../i18n/translator';
import {serverValidationMessage} from '../validation/server.ts';

export type ApiErrorMessageCandidate = {
  serverMessage?: string;
  errorCode?: string;
  userMessage?: string;
  fallback?: string;
  errorMessage: string;
};

const machineCodePattern = /^[A-Z][A-Z0-9_]{2,}$/;

export const isMachineErrorCode = (value: string) => machineCodePattern.test(value);

/** Only a bounded translation key is accepted, never arbitrary parameters or HTML from an error. */
export function translatedServerMessage(data: unknown, translate: (key: string, fallback: string) => string): string | undefined {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return undefined;
  const {messageKey} = data as Record<string, unknown>;
  if (typeof messageKey !== 'string' || !/^[a-zA-Z][a-zA-Z0-9._-]{0,199}$/.test(messageKey)) return undefined;
  const translated = translate(messageKey, '');
  return translated && translated !== messageKey ? translated : undefined;
}

/** Field-level validation remains more useful than the envelope's generic translation key. */
export function localizedServerMessage(data: unknown, translate: TranslateFn): string | undefined {
  return serverValidationMessage(data, translate) ?? translatedServerMessage(data, translate);
}

/** Selects a user-facing message without rendering a machine error code. */
export const selectApiErrorMessage = ({
  serverMessage,
  errorCode,
  userMessage,
  fallback,
  errorMessage,
}: ApiErrorMessageCandidate) => {
  if (serverMessage && !isMachineErrorCode(serverMessage)) {
    return serverMessage;
  }
  if (errorCode && fallback) return fallback;
  const safeUserMessage = userMessage && !isMachineErrorCode(userMessage)
    ? userMessage
    : undefined;
  return safeUserMessage || fallback || errorMessage;
};
