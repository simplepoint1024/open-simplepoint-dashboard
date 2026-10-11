import type {Scalar} from './query';

export const isNumericQueryType = (type?: string, javaType?: string) => type === 'number' || type === 'integer'
  || ['BigDecimal', 'BigInteger', 'Byte', 'byte', 'Short', 'short', 'Integer', 'int', 'Long', 'long', 'Float', 'float', 'Double', 'double'].includes(javaType ?? '');

/** A decimal can round to a safe integer; only literal integers may use the number representation. */
export function queryScalar(value: string, type?: string, javaType?: string): Scalar {
  if (type === 'boolean') return value === 'true';
  if (javaType === 'BigDecimal') return value;
  return isNumericQueryType(type, javaType) && /^[+-]?\d+$/.test(value) && Number.isSafeInteger(Number(value)) ? Number(value) : value;
}
