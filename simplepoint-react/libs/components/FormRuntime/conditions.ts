import type {FormCondition, FormValue, FormFieldType} from './contracts.ts';
import {compareDecimals, exactDecimal} from '../../shared/utils/decimal.ts';
import {temporalKey} from './literals.ts';
import {emptyJsonValue, jsonValue} from '../../shared/utils/jsonValue.ts';

/** Declarative rules use typed literals; invalid actual values never satisfy ordered comparisons. */
export function conditionMatches(condition: FormCondition, values: Record<string, unknown>,
  fields?: ReadonlyMap<string, {type: FormFieldType}>): boolean {
  switch (condition.operator) {
    case 'AND': return condition.children.every(child => conditionMatches(child, values, fields));
    case 'OR': return condition.children.some(child => conditionMatches(child, values, fields));
    case 'NOT': return !conditionMatches(condition.children[0], values, fields);
  }
  const actual = condition.fieldId ? values[condition.fieldId] : undefined;
  const compare = (value: FormValue) => compareLiteral(actual, value);
  switch (condition.operator) {
    case 'IS_EMPTY': return emptyFormValue(actual, fields?.get(condition.fieldId!)?.type);
    case 'IS_NOT_EMPTY': return !emptyFormValue(actual, fields?.get(condition.fieldId!)?.type);
    case 'EQUALS': return compare(condition.values[0]) === 0;
    case 'NOT_EQUALS': return compare(condition.values[0]) !== 0;
    case 'IN': return condition.values.some(value => compare(value) === 0);
    case 'NOT_IN': return condition.values.every(value => compare(value) !== 0);
    case 'GREATER_THAN': return compare(condition.values[0]) > 0;
    case 'GREATER_THAN_OR_EQUAL': return compare(condition.values[0]) >= 0;
    case 'LESS_THAN': return compare(condition.values[0]) < 0;
    case 'LESS_THAN_OR_EQUAL': return compare(condition.values[0]) <= 0;
    default: return false;
  }
}

function compareLiteral(actual: unknown, expected: FormValue): number {
  if (actual == null || !expected || expected.kind === 'NULL') return Number.NaN;
  const text = expected.value ?? '';
  try {
    if (expected.kind === 'JSON') return jsonValue(actual).canonical === jsonValue(text).canonical ? 0 : Number.NaN;
    if (expected.kind === 'INTEGER' || expected.kind === 'DECIMAL') return compareDecimals(exactDecimal(actual), exactDecimal(text));
    if (expected.kind === 'DATE' || expected.kind === 'TIME' || expected.kind === 'DATETIME') {
      if (typeof actual !== 'string') return Number.NaN;
      const a = temporalKey(expected.kind, actual), b = temporalKey(expected.kind, text);
      return a === b ? 0 : a > b ? 1 : -1;
    }
    if (expected.kind === 'BOOLEAN') return typeof actual === 'boolean' && actual === (text === 'true') ? 0 : Number.NaN;
    if (typeof actual !== 'string') return Number.NaN;
    const left = expected.kind === 'UUID' ? actual.toLowerCase() : actual;
    const right = expected.kind === 'UUID' ? text.toLowerCase() : text;
    return left === right ? 0 : left > right ? 1 : -1;
  } catch {return Number.NaN;}
}

export const emptyFormValue = (value: unknown, type?: FormFieldType): boolean => type === 'JSON' ? emptyJsonValue(value)
  : value == null || typeof value === 'string' && value.trim() === ''
  || Array.isArray(value) && value.length === 0
  || typeof value === 'object' && Object.keys(value).length === 0;
