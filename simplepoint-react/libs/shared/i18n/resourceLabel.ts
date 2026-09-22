/** Translate a resource alias without exposing an untranslated i18n key. */
export function resolveResourceLabel(value: string | null | undefined, fallback: string, t: (key: string, fallback: string) => string): string {
  if (!value) return fallback;
  if (!value.startsWith('i18n:')) return value;
  const key = value.slice(5);
  const translated = t(key, fallback);
  return translated === key || translated.startsWith('i18n:') ? fallback : translated;
}
