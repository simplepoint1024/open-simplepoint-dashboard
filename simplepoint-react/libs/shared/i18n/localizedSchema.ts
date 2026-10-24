import {canonicalLocale, resolveLocalizedText, type LocalizedText} from './localizedText.ts';

/** Same versioned contract as @LocalizedField; used by authored projection forms. */
export function localizedTextSchema(title: string, maxLength = 120) {
  const text = {type: 'string' as const, minLength: 1, maxLength, pattern: '\\S'};
  return {type: 'object' as const, title, additionalProperties: false,
    required: ['defaultValue', 'defaultLocale', 'translations'], properties: {
      defaultValue: text, defaultLocale: {type: 'string' as const, minLength: 2, maxLength: 64},
      translations: {type: 'object' as const, maxProperties: 32, additionalProperties: text},
    }, 'x-localized': {version: 1, maxLength, maxLanguages: 32}, 'x-ui': {'ui:field': 'LocalizedText'}};
}

/** Only materialize legacy metadata after an actual authored change. */
export function localizedDraft(label: string, value: LocalizedText | null | undefined, locale: string): LocalizedText {
  return value ?? {defaultValue: label, defaultLocale: canonicalLocale(locale), translations: {}};
}
export function changedLocalizedDraft(label: string, previous: LocalizedText | null | undefined,
  next: LocalizedText | undefined, locale: string): LocalizedText | null | undefined {
  return !next || JSON.stringify(localizedDraft(label, previous, locale)) === JSON.stringify(next) ? previous : next;
}

/** Literal authored titles never enter the platform translation-key namespace. */
export function localizeSchemaTitles<T>(schema: T, locale: string): T {
  if (!schema || typeof schema !== 'object') return schema;
  if (Array.isArray(schema)) return schema.map(item => localizeSchemaTitles(item, locale)) as T;
  const source = schema as Record<string, any>, result = {...source};
  if (source['x-title-localization']) result.title = resolveLocalizedText(source['x-title-localization'], locale, undefined, 'TENANT', source.title).text;
  for (const key of ['properties', '$defs', 'definitions']) if (source[key])
    result[key] = Object.fromEntries(Object.entries(source[key]).map(([name, value]) => [name, localizeSchemaTitles(value, locale)]));
  for (const key of ['items', 'oneOf', 'anyOf', 'allOf']) if (source[key]) result[key] = localizeSchemaTitles(source[key], locale);
  return result as T;
}
