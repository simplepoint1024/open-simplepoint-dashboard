export type Messages = Record<string, string>;

export type TranslateFn = (
  key: string,
  fallbackOrParams?: string | Record<string, unknown>,
  maybeParams?: Record<string, unknown>
) => string;

export type MissingKeyHandler = (key: string) => void;

export type TranslatorOptions = {
  onMissing?: MissingKeyHandler;
};

const parseTranslateArgs = (
  fallbackOrParams?: string | Record<string, unknown>,
  maybeParams?: Record<string, unknown>
) => {
  const fallback = typeof fallbackOrParams === 'string' ? fallbackOrParams : undefined;
  const params =
    typeof fallbackOrParams === 'object'
      ? fallbackOrParams
      : typeof maybeParams === 'object'
        ? maybeParams
        : undefined;

  return { fallback, params };
};

const getParamValue = (params: Record<string, unknown>, key: string): unknown => {
  if (Object.prototype.hasOwnProperty.call(params, key)) {
    return params[key];
  }

  return key.split('.').reduce<unknown>((value, part) => {
    if (value == null || typeof value !== 'object') return undefined;
    return (value as Record<string, unknown>)[part];
  }, params);
};

export const interpolate = (template: string, params?: Record<string, unknown>) => {
  if (!params) return template;

  return template.replace(/\{([^}]+)}/g, (match, rawKey: string) => {
    const value = getParamValue(params, rawKey.trim());
    return value !== undefined ? String(value) : match;
  });
};

export const createTranslator = (
  messages: Messages,
  options: TranslatorOptions = {}
): TranslateFn => (key, fallbackOrParams, maybeParams) => {
  const { fallback, params } = parseTranslateArgs(fallbackOrParams, maybeParams);
  const hasMessage = Object.prototype.hasOwnProperty.call(messages, key);

  if (!hasMessage) {
    options.onMissing?.(key);
  }

  const raw = hasMessage ? messages[key] : (fallback ?? key);
  return interpolate(raw, params);
};

export const normalizeNamespaces = (namespaces: string[]) =>
  Array.from(new Set(namespaces.map(ns => ns.trim()).filter(Boolean))).sort();
