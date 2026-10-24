/** Version 1 field-value contract. These values stay in their authorized owning record. */
export type LocalizedText = {defaultValue: string; defaultLocale: string; translations: Record<string, string>};
export type LocalizedSource = 'TENANT_CUSTOM' | 'PLATFORM_CUSTOM' | 'PLATFORM_INHERITED' | 'DEFAULT' | 'MISSING';
export type LocalizedResolution = {text: string; source: LocalizedSource};

export function canonicalLocale(value: string): string {
  const canonical = Intl.getCanonicalLocales(value.replace(/_/g, '-'))[0];
  if (!canonical || canonical === 'und' || canonical.length > 64 || /-[0-9a-z]-/i.test(canonical)) throw new Error('form.localized.invalid');
  return canonical;
}

export function localizedOverride(value: LocalizedText | null | undefined, locale: string): string | undefined {
  // Field-policy projections may replace the whole object with a masked scalar.
  if (!value || typeof value !== 'object' || !value.translations || Array.isArray(value.translations)) return undefined;
  const tag = canonicalLocale(locale);
  return value.translations[tag] ?? value.translations[tag.split('-')[0]];
}

export function resolveLocalizedText(value: LocalizedText | null | undefined, locale: string,
  inherited?: string, scope: 'PLATFORM' | 'TENANT' = 'TENANT', fallback = ''): LocalizedResolution {
  const override = localizedOverride(value, locale);
  if (override !== undefined) return {text: override, source: scope === 'TENANT' ? 'TENANT_CUSTOM' : 'PLATFORM_CUSTOM'};
  if (inherited) return {text: inherited, source: 'PLATFORM_INHERITED'};
  const text = value?.defaultValue || fallback;
  return {text, source: text ? 'DEFAULT' : 'MISSING'};
}

export function editLocalizedText(value: LocalizedText, locale: string, text: string): LocalizedText {
  const tag = canonicalLocale(locale);
  return {...value, defaultValue: tag === canonicalLocale(value.defaultLocale) ? text : value.defaultValue,
    translations: {...value.translations, [tag]: text}};
}

export function inheritLocalizedText(value: LocalizedText, locale: string, inherited?: string): LocalizedText {
  const translations = {...value.translations};
  delete translations[canonicalLocale(locale)];
  return {...value, translations,
    defaultValue: canonicalLocale(locale) === canonicalLocale(value.defaultLocale) && inherited ? inherited : value.defaultValue};
}

export function localizedTextValid(value: LocalizedText, maxLength = 255, maxLanguages = 32): boolean {
  try {
    canonicalLocale(value.defaultLocale);
    const keys = Object.keys(value.translations), normalized = keys.map(canonicalLocale);
    return keys.length <= maxLanguages && new Set(normalized).size === keys.length
      && [value.defaultValue, ...Object.values(value.translations)].every(text =>
        typeof text === 'string' && text.trim().length > 0 && [...text].length <= maxLength);
  } catch {return false;}
}

/** Navigation keeps platform translation keys unless this exact tenant field has an override. */
export function localizeRouteNames<T extends {nameLocalization?: LocalizedText | null; label?: string; title?: string; children?: T[]}>(
  routes: T[], locale: string): T[] {
  return routes.map(route => {
    const override = localizedOverride(route.nameLocalization, locale);
    return {...route, ...(override !== undefined ? {label: override, title: ''}
      : valueDefault(route) ? {label: valueDefault(route)} : {}),
      ...(route.children ? {children: localizeRouteNames(route.children, locale)} : {})};
  });
}

function valueDefault(route: {nameLocalization?: LocalizedText | null}): string | undefined {
  return route.nameLocalization?.defaultValue;
}
