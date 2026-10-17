import {exactDecimal} from './decimal.ts';

export type JsonValue = {canonical: string; empty: boolean};

/** Lossless, bounded JSON semantics. Numbers never pass through JavaScript's floating-point parser. */
export function jsonValue(source: unknown): JsonValue {
  if (typeof source !== 'string' || source.length > 1048576) throw Error('Invalid JSON value');
  let position = 0, nodes = 0;
  const invalid = (): never => {throw Error('JSON value is invalid or exceeds its complexity budget');};
  const whitespace = () => {while (/[\x20\t\r\n]/.test(source[position] ?? '\x00')) position++;};
  const string = (): string => {
    const start = position++;
    while (position < source.length) {
      const character = source[position++];
      if (character === '\\') {position++; continue;}
      if (character === '"') {
        const value: string = JSON.parse(source.slice(start, position));
        for (let index = 0; index < value.length; index++) {
          const code = value.charCodeAt(index);
          if (code >= 0xd800 && code <= 0xdbff) {
            const low = value.charCodeAt(++index);
            if (!(low >= 0xdc00 && low <= 0xdfff)) invalid();
          } else if (code >= 0xdc00 && code <= 0xdfff) invalid();
        }
        return value;
      }
    }
    return invalid();
  };
  const parse = (depth: number): JsonValue => {
    if (depth > 64 || ++nodes > 10000) invalid();
    whitespace();
    const first = source[position];
    if (first === '"') {
      const value = string();
      return {canonical: JSON.stringify(value), empty: value.trim() === ''};
    }
    if (first === '{' || first === '[') {
      if (depth >= 64) invalid();
      const object = first === '{', end = object ? '}' : ']';
      const entries = new Map<string, string>(), items: string[] = [];
      position++; whitespace();
      if (source[position] === end) {position++; return {canonical: object ? '{}' : '[]', empty: true};}
      while (position < source.length) {
        if (object) {
          whitespace();
          if (source[position] !== '"') invalid();
          const key = string();
          if (entries.has(key)) invalid();
          whitespace();
          if (source[position++] !== ':') invalid();
          entries.set(key, parse(depth + 1).canonical);
        } else items.push(parse(depth + 1).canonical);
        whitespace();
        const next = source[position++];
        if (next === end) return {canonical: object
          ? `{${[...entries.keys()].sort().map(key => `${JSON.stringify(key)}:${entries.get(key)}`).join(',')}}`
          : `[${items.join(',')}]`, empty: false};
        if (next !== ',') invalid();
      }
      return invalid();
    }
    for (const literal of ['null', 'true', 'false']) if (source.startsWith(literal, position)) {
      position += literal.length;
      return {canonical: literal, empty: literal === 'null'};
    }
    const match = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(source.slice(position));
    if (!match) return invalid();
    position += match[0].length;
    let {coefficient, scale} = exactDecimal(match[0]);
    if (Math.abs(scale) > 1000) invalid();
    if (coefficient === BigInt(0)) return {canonical: '0', empty: false};
    while (coefficient % BigInt(10) === BigInt(0)) {coefficient /= BigInt(10); scale--;}
    if (Math.abs(scale) > 1000) invalid();
    const negative = coefficient < BigInt(0), digits = (negative ? -coefficient : coefficient).toString();
    const padded = digits.padStart(scale + 1, '0');
    const number = scale <= 0 ? digits + '0'.repeat(-scale) : `${padded.slice(0, -scale)}.${padded.slice(-scale)}`;
    const canonical = (negative ? '-' : '') + number;
    if (canonical.length > 2000) invalid();
    return {canonical, empty: false};
  };
  const result = parse(0);
  whitespace();
  if (position !== source.length || result.canonical.length > 1048576) invalid();
  return result;
}

/** An unfilled editor is missing; malformed nonblank JSON is separately rejected by field validation. */
export function emptyJsonValue(value: unknown): boolean {
  if (value == null || typeof value === 'string' && value.trim() === '') return true;
  try {return jsonValue(value).empty;} catch {return false;}
}
