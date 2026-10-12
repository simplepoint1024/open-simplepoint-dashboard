import type {ErrorSchema} from '@rjsf/utils';
import type {TranslateFn} from '../i18n/translator';
import {validationMessage} from './messages.ts';

export type FieldViolation = {field: string; keyword: string; params: Record<string, unknown>; messageKey?: string};

/** Read only the bounded, explicitly typed validation contract; never display rejected values. */
export function serverViolations(data: unknown): FieldViolation[] | undefined {
  if (!data || typeof data !== 'object' || (data as any).code !== 'VALIDATION_FAILED') return undefined;
  const values = (data as any).fieldErrors;
  if (!Array.isArray(values)) return [];
  return values.slice(0, 100).filter(value => value && typeof value.field === 'string' && value.field.length <= 256
    && typeof value.keyword === 'string' && /^[a-zA-Z]+$/.test(value.keyword)
    && value.params && typeof value.params === 'object').map(value => ({
      field: value.field, keyword: value.keyword,
      params: Object.fromEntries(Object.entries(value.params).filter(([key, item]) =>
        ['limit', 'min', 'max', 'type', 'format', 'multipleOf'].includes(key)
        && (typeof item === 'string' && item.length <= 100 || typeof item === 'number'))),
      messageKey: typeof value.messageKey === 'string' && /^[a-zA-Z][a-zA-Z0-9._-]{0,199}$/.test(value.messageKey)
        ? value.messageKey : undefined,
    }));
}

export function violationMessage(violation: FieldViolation, t: TranslateFn) {
  const fallback = validationMessage(violation.keyword, violation.params, t);
  return violation.messageKey ? t(violation.messageKey, fallback, violation.params) : fallback;
}

export function serverValidationMessage(data: unknown, t: TranslateFn): string | undefined {
  const violations = serverViolations(data);
  if (!violations) return undefined;
  const details = [...new Set(violations.map(violation => violationMessage(violation, t)))].slice(0, 3);
  return details.length ? details.join('；') : t('form.validation.failed', '请检查表单中的内容后重试');
}

/** Attach only to fields actually present and writable in this form; reject prototype/path injection. */
export function serverErrorSchema(violations: FieldViolation[] | undefined, schema: any, t: TranslateFn): ErrorSchema {
  const errors: any = {};
  const resolve = (value: any): any => {
    const seen = new Set();
    while (typeof value?.$ref === 'string' && value.$ref.startsWith('#/') && !seen.has(value.$ref)) {
      seen.add(value.$ref);
      const referred = value.$ref.slice(2).split('/').reduce((node: any, part: string) =>
        node?.[part.replace(/~1/g, '/').replace(/~0/g, '~')], schema);
      if (!referred || typeof referred !== 'object') return undefined;
      value = {...referred, ...Object.fromEntries(Object.entries(value).filter(([key]) => key !== '$ref'))};
    }
    return value;
  };
  for (const violation of violations ?? []) {
    const parts = violation.field.replace(/\[(\d+)\]/g, '.$1').split('.');
    if (parts.some(part => !/^[a-zA-Z0-9_-]+$/.test(part) || ['__proto__', 'prototype', 'constructor'].includes(part))) continue;
    let current = resolve(schema), valid = true;
    for (const part of parts) {
      if (current?.readOnly || current?.['x-field-access'] && current['x-field-access'] !== 'EDITABLE') {valid = false; break;}
      const array = current?.type === 'array' || Array.isArray(current?.type) && current.type.includes('array');
      current = resolve(array && /^\d+$/.test(part) ? current.items : current?.properties?.[part]);
      if (!current || current.readOnly || current['x-field-access'] && current['x-field-access'] !== 'EDITABLE') {valid = false; break;}
    }
    if (!valid) continue;
    let target = errors;
    for (const part of parts) target = target[part] ??= {};
    (target.__errors ??= []).push(violationMessage(violation, t));
  }
  return errors;
}
