import type {FormFieldType, FormValue, FormValueKind} from './contracts.ts';
import {choiceValue} from './choiceValues.ts';
import {jsonValue} from '../../shared/utils/jsonValue.ts';

const kinds: Record<FormFieldType, FormValueKind> = {STRING: 'STRING', TEXT: 'STRING', BOOLEAN: 'BOOLEAN',
  INT32: 'INTEGER', INT64: 'INTEGER', DECIMAL: 'DECIMAL', DATE: 'DATE', TIME: 'TIME', DATETIME: 'DATETIME', UUID: 'UUID', JSON: 'JSON'};

export const fieldValueKind = (type: FormFieldType) => kinds[type];

export function typedFormLiteral(type: FormFieldType, value: string): FormValue {
  return {kind: kinds[type], value: normalizeFormLiteral(type, value)};
}

function validDate(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return false;
  const [, year, month, day] = match.map(Number), leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  return month >= 1 && month <= 12 && day >= 1 && day <= [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1];
}
const validTime = (value: string) => /^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?$/.test(value);

export function normalizeFormLiteral(type: FormFieldType, value: string): string {
  if (typeof value !== 'string' || value.length > 16384) throw Error('Invalid literal');
  if (type === 'JSON') {
    jsonValue(value); // Structural validation is lossless; the editor keeps the original text.
    return value;
  }
  if (type === 'DATE' && !validDate(value) || type === 'TIME' && !validTime(value)
    || type === 'UUID' && !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value)) throw Error('Invalid literal');
  if (type === 'DATETIME') {
    const parts = /^(\d{4}-\d{2}-\d{2})T(.+?)(Z|[+-]\d{2}:\d{2})$/.exec(value);
    if (!parts || !validDate(parts[1]) || !validTime(parts[2]) || parts[2].split(':').length !== 3
      || !Number.isFinite(Date.parse(value))) throw Error('Invalid instant');
  }
  const result = String(choiceValue(type, value));
  return result;
}

/** Ordered temporal key with nanosecond precision; offsets refer to the same instant. */
export function temporalKey(type: 'DATE' | 'TIME' | 'DATETIME', text: string): bigint {
  normalizeFormLiteral(type, text);
  if (type === 'DATE') return BigInt(text.replace(/-/g, ''));
  const fraction = /\.(\d+)/.exec(text)?.[1] ?? '';
  const nanos = BigInt(fraction.padEnd(9, '0'));
  if (type === 'DATETIME') return BigInt(Date.parse(text.replace(/\.\d+/, ''))) * 1000000n + nanos;
  const [hour, minute, second = 0] = text.split('.')[0].split(':').map(Number);
  return BigInt(hour * 3600 + minute * 60 + second) * 1000000000n + nanos;
}
