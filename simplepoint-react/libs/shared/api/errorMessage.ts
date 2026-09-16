export type ApiErrorMessageCandidate = {
  serverMessage?: string;
  errorCode?: string;
  userMessage?: string;
  fallback?: string;
  errorMessage: string;
};

const machineCodePattern = /^[A-Z][A-Z0-9_]{2,}$/;

export const isMachineErrorCode = (value: string) => machineCodePattern.test(value);

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
