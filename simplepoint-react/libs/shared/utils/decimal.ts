export type ExactDecimal = {coefficient: bigint; scale: number};

/** Bounded decimal arithmetic for transport/form/query values, never IEEE-754 coercion. */
export function exactDecimal(value: unknown): ExactDecimal {
  if (typeof value !== 'string' && typeof value !== 'number') throw Error('Invalid decimal value');
  const text = String(value), match = text.match(/^([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/);
  if (!match || !(match[2] || match[3]) || text.length > 2000) throw Error('Invalid decimal value');
  const exponent = Number(match[4] ?? 0);
  if (!Number.isInteger(exponent) || Math.abs(exponent) > 1000) throw Error('Decimal exponent out of bounds');
  return {coefficient: BigInt(`${match[1]}${match[2] || '0'}${match[3] || ''}`), scale: (match[3]?.length ?? 0) - exponent};
}

export function compareDecimals(a: ExactDecimal, b: ExactDecimal): number {
  const scale = Math.max(a.scale, b.scale);
  const x = a.coefficient * BigInt(`1${'0'.repeat(scale - a.scale)}`);
  const y = b.coefficient * BigInt(`1${'0'.repeat(scale - b.scale)}`);
  return x < y ? -1 : x > y ? 1 : 0;
}
