import {exactDecimal, compareDecimals} from '@simplepoint/shared/utils/decimal.ts';

type DecimalRule = {precision?: number; scale?: number; minimum?: string; maximum?: string; exclusiveMinimum?: boolean; exclusiveMaximum?: boolean};
export type DecimalIssue = 'format' | 'scale' | 'minimum' | 'maximum';
const zero = BigInt(0), ten = BigInt(10);

export function decimalIssue(value: unknown, rule: DecimalRule): DecimalIssue | undefined {
  if (value == null) return undefined;
  if (typeof value !== 'string') return 'format';
  try {
    const decimal = exactDecimal(value);
    // Match server UNNECESSARY rounding: harmless trailing zeroes never change the value.
    let coefficient = decimal.coefficient, scale = decimal.scale;
    if (coefficient === zero) scale = 0;
    else while (scale > (rule.scale ?? scale) && coefficient % ten === zero) {coefficient /= ten; scale--;}
    if (Number.isSafeInteger(rule.scale) && coefficient !== zero && scale > rule.scale!) return 'scale';
    if (rule.minimum !== undefined) {
      const result = compareDecimals(decimal, exactDecimal(rule.minimum));
      if (result < 0 || result === 0 && rule.exclusiveMinimum) return 'minimum';
    }
    if (rule.maximum !== undefined) {
      const result = compareDecimals(decimal, exactDecimal(rule.maximum));
      if (result > 0 || result === 0 && rule.exclusiveMaximum) return 'maximum';
    }
  } catch {return 'format';}
}

/** Extend the existing engine's custom validation; readonly/masked values remain server-owned. */
export function validateDecimalFields(schema: any, value: any, errors: any,
  message: (issue: DecimalIssue, rule: DecimalRule) => string, root = schema, references = new Set<object>()) {
  if (!schema || typeof schema !== 'object' || !errors || value == null) return;
  if (schema.readOnly === true || schema['x-field-access'] != null && schema['x-field-access'] !== 'EDITABLE') return;
  if (typeof schema.$ref === 'string' && schema.$ref.startsWith('#/')) {
    const resolved = schema.$ref.slice(2).split('/').reduce((node: any, part: string) => node?.[part.replace(/~1/g, '/').replace(/~0/g, '~')], root);
    if (resolved && resolved !== schema && !references.has(resolved)) {
      const branch = new Set(references); branch.add(schema); branch.add(resolved);
      validateDecimalFields(resolved, value, errors, message, root, branch);
    }
  }
  const rule = schema['x-decimal'];
  if (rule && typeof rule === 'object' && !Array.isArray(rule)) {
    const issue = decimalIssue(value, rule); if (issue) errors.addError(message(issue, rule));
  }
  for (const [field, child] of Object.entries(schema.properties ?? {})) if (Object.prototype.hasOwnProperty.call(value, field))
    validateDecimalFields(child, value[field], errors[field], message, root);
  if (Array.isArray(value) && schema.items && !Array.isArray(schema.items)) value.forEach((item, index) =>
    validateDecimalFields(schema.items, item, errors[index], message, root));
}
