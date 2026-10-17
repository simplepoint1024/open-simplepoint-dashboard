import type {FormFieldType} from './contracts.ts';
import {exactDecimal} from '../../shared/utils/decimal.ts';

/** Match the server's scalar choice transport without rounding long integers or decimals. */
export function choiceValue(type: FormFieldType, value: string): string | number | boolean {
  if (type === 'BOOLEAN') {
    if (value !== 'true' && value !== 'false') throw new Error('Invalid boolean choice');
    return value === 'true';
  }
  if (type === 'INT32' || type === 'INT64' || type === 'DECIMAL') {
    let {coefficient, scale} = exactDecimal(value);
    if (coefficient === 0n) scale = 0;
    while (scale > 0 && coefficient % 10n === 0n) {coefficient /= 10n; scale--;}
    if (type !== 'DECIMAL' && scale > 0) throw new Error('Invalid integer choice');
    const negative = coefficient < 0n, digits = (negative ? -coefficient : coefficient).toString();
    const padded = digits.padStart(scale + 1, '0');
    const text = (negative ? '-' : '') + (scale <= 0 ? digits + '0'.repeat(-scale)
      : `${padded.slice(0, -scale)}.${padded.slice(-scale)}`);
    if (type === 'DECIMAL') return text;
    const integer = BigInt(text);
    const min = type === 'INT32' ? -2147483648n : -9223372036854775808n;
    const max = type === 'INT32' ? 2147483647n : 9223372036854775807n;
    if (integer < min || integer > max) throw new Error('Integer choice out of range');
    return type === 'INT32' ? Number(integer) : text;
  }
  if (type === 'JSON') throw new Error('JSON fields cannot declare scalar choices');
  return value;
}
