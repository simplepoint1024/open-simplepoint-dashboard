import {compareDecimals, exactDecimal, type ExactDecimal} from '../../shared/utils/decimal.ts';
import type {ApplicationFormDocument, FormValidationConstraint} from './contracts.ts';

const pow = (scale: number) => BigInt(10) ** BigInt(scale);
function sum(values: unknown[]): ExactDecimal {
  return values.map(exactDecimal).reduce((a, b) => {
    const scale = Math.max(a.scale, b.scale);
    return {coefficient: a.coefficient * pow(scale - a.scale) + b.coefficient * pow(scale - b.scale), scale};
  }, {coefficient: BigInt(0), scale: 0});
}
function text(value: ExactDecimal): string {
  const sign = value.coefficient < BigInt(0) ? '-' : '';
  const digits = (value.coefficient < BigInt(0) ? -value.coefficient : value.coefficient).toString();
  if (value.scale <= 0) return sign + digits + '0'.repeat(-value.scale);
  const padded = digits.padStart(value.scale + 1, '0');
  return sign + padded.slice(0, -value.scale) + '.' + padded.slice(-value.scale);
}
const rows = (values: Record<string, unknown>, rule: FormValidationConstraint): Record<string, unknown>[] => {
  const items = values[rule.detailId!];
  if (!Array.isArray(items) || items.length > 40) throw Error('Invalid details');
  return items.map(item => item.values);
};

/** Derived display totals remain ordinary submitted values, rechecked exactly by the server. */
export function deriveFormTotals(document: ApplicationFormDocument, values: Record<string, unknown>) {
  const result = {...values};
  for (const rule of document.constraints ?? []) if (rule.kind === 'DETAIL_SUM') {
    try {result[rule.fieldId!] = text(sum(rows(values, rule).map(row => row[rule.detailFieldId!])));}
    catch {result[rule.fieldId!] = undefined;}
  }
  return result;
}

export function aggregateIssues(document: ApplicationFormDocument, values: Record<string, unknown>) {
  return (document.constraints ?? []).filter(rule => {
    const range = (value: unknown) => {
      const n = exactDecimal(value);
      return (rule.minimum == null || compareDecimals(n, exactDecimal(rule.minimum)) >= 0)
        && (rule.maximum == null || compareDecimals(n, exactDecimal(rule.maximum)) <= 0);
    };
    try {
      switch (rule.kind) {
        case 'RANGE': return !range(values[rule.fieldId!]);
        case 'AFTER': {
          const a = values[rule.fieldId!], b = values[rule.otherFieldId!];
          if (typeof a !== 'string' || typeof b !== 'string' || !a || !b) return true;
          const type = document.fields.find(field => field.fieldId === rule.fieldId)?.type;
          return type === 'DATETIME' ? !(Date.parse(a) > Date.parse(b)) : !(a > b);
        }
        case 'DETAIL_COUNT': return !range(rows(values, rule).length);
        case 'DETAIL_RANGE': return !rows(values, rule).every(row => range(row[rule.detailFieldId!]));
        case 'DETAIL_SUM': return compareDecimals(exactDecimal(values[rule.fieldId!]),
          sum(rows(values, rule).map(row => row[rule.detailFieldId!]))) !== 0;
      }
    } catch {return true;}
  }).map(rule => ({fieldId: rule.fieldId ?? rule.detailId!, message: rule.message}));
}
