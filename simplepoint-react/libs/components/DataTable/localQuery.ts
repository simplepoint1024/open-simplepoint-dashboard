import {filterNode, operatorLabels, validateView, type QueryContract, type QueryNode} from '../Table/query.ts';
import {exactDecimal as decimal, compareDecimals as decimalCompare} from '@simplepoint/shared/utils/decimal.ts';

export type LocalField = {
  type: 'string' | 'number' | 'integer' | 'boolean' | 'date' | 'date-time' | 'local-date-time';
  label?: string;
  /** Trusted source-code mapping; never supplied by a saved view. */
  path?: readonly (string | number)[];
  columnKey?: string;
  enum?: readonly (string | number | boolean)[];
};
export type LocalFields = Record<string, LocalField>;
export type TableQueryState = {filters: Record<string, string>; sort?: string | string[]};
export type QuerySchema = {properties: Record<string, any>; 'x-query': QueryContract};

const javaTypes = {string: 'String', number: 'BigDecimal', integer: 'BigInteger', boolean: 'Boolean',
  date: 'LocalDate', 'date-time': 'Instant', 'local-date-time': 'LocalDateTime'};

/** Explicit columns only; no guessing types from the first page or from nullable sample values. */
export function localQuerySchema(fields: LocalFields): QuerySchema {
  const properties: QuerySchema['properties'] = {};
  const capabilities: QueryContract['fields'] = {};
  for (const [name, definition] of Object.entries(fields)) {
    if (!/^[A-Za-z][A-Za-z0-9_]{0,79}$/.test(name)) throw Error('Invalid local query field alias');
    const text = definition.type === 'string';
    capabilities[name] = {javaType: javaTypes[definition.type], sortable: true,
      operators: Object.keys(operatorLabels).filter(op => (text || !op.includes('like'))
        && (definition.type !== 'boolean' || !(op.includes('between') || op.startsWith('than:'))))};
    properties[name] = {title: definition.label ?? name,
      type: ['date', 'date-time', 'local-date-time'].includes(definition.type) ? 'string' : definition.type,
      ...(definition.type === 'date' ? {format: 'date'} : {}),
      ...(['date-time', 'local-date-time'].includes(definition.type) ? {format: 'date-time'} : {}),
      ...(definition.enum ? {enum: [...definition.enum]} : {})};
  }
  return {properties, 'x-query': {version: 1, maxSort: 3, maxDepth: 4, maxNodes: 32, fields: capabilities}};
}

function temporal(value: unknown, type: LocalField['type']): string | ReturnType<typeof decimal> {
  if (typeof value !== 'string') throw Error('Invalid date value');
  const date = value.match(/^(\d{4})-(\d{2})-(\d{2})(.*)$/);
  if (!date) throw Error('Invalid date value');
  const day = new Date(`${date[1]}-${date[2]}-${date[3]}T00:00:00Z`);
  if (!Number.isFinite(day.getTime()) || day.toISOString().slice(0, 10) !== value.slice(0, 10)) throw Error('Invalid date value');
  if (type === 'date') {
    if (date[4] !== '') throw Error('Invalid date value');
    return value;
  }
  const time = date[4].match(/^T([01]\d|2[0-3]):([0-5]\d):([0-5]\d)(?:\.(\d{1,9}))?(Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)?$/);
  if (!time || (type === 'local-date-time' ? Boolean(time[5]) : !time[5])) throw Error('Invalid time value');
  if (type === 'local-date-time') return `${value.slice(0, 19)}.${(time[4] ?? '').padEnd(9, '0')}`;
  const instant = Date.parse(value);
  if (!Number.isFinite(instant)) throw Error('Invalid time value');
  // Preserve sub-millisecond precision instead of rounding timestamp equality to milliseconds.
  return {coefficient: BigInt(Math.floor(instant / 1000)) * BigInt(1000000000)
    + BigInt((time[4] ?? '').padEnd(9, '0')), scale: 0};
}

type Value = string | boolean | number | ReturnType<typeof decimal>;
function typed(value: unknown, field: LocalField): Value | null {
  if (value == null) return null;
  if (field.type === 'number' || field.type === 'integer') return decimal(value);
  if (field.type === 'boolean') {
    if (typeof value !== 'boolean') throw Error('Invalid boolean value');
    return value;
  }
  if (field.type !== 'string') return temporal(value, field.type);
  if (typeof value !== 'string') throw Error('Invalid text value');
  return value;
}

function compare(a: Value, b: Value): number {
  if (typeof a === 'object' || typeof b === 'object') {
    if (typeof a !== 'object' || typeof b !== 'object') throw Error('Incompatible query values');
    return decimalCompare(a, b);
  }
  return a < b ? -1 : a > b ? 1 : 0;
}

function fieldValue(row: unknown, alias: string, field: LocalField): Value | null {
  let value: any = row;
  for (const key of field.path ?? [alias]) {
    if (value == null || !Object.prototype.hasOwnProperty.call(value, key)) return null;
    value = value[key];
  }
  try {return typed(value, field);} catch {return null;}
}

type Truth = boolean | null;
type Predicate = (row: unknown) => Truth;
const not = (value: Truth): Truth => value === null ? null : !value;

function compile(node: QueryNode, fields: LocalFields): Predicate {
  if (node.op === 'not') {const child = compile(node.children![0], fields); return row => not(child(row));}
  if (node.op === 'and' || node.op === 'or') {
    const children = node.children!.map(child => compile(child, fields));
    return row => {
      const decisive = node.op === 'or';
      let unknown = false;
      for (const child of children) {
        const value = child(row);
        if (value === decisive) return decisive;
        unknown ||= value === null;
      }
      return unknown ? null : !decisive;
    };
  }
  const field = fields[node.field!];
  const input = node.value === undefined ? [] : Array.isArray(node.value) ? node.value : [node.value];
  const values = input.map(value => typed(value, field)!);
  if (node.op.includes('between') && compare(values[0], values[1]) > 0) throw Error('区间起始值不能大于结束值');
  return row => {
    const value = fieldValue(row, node.field!, field);
    if (node.op === 'is:null') return value === null;
    if (node.op === 'is:not:null') return value !== null;
    if (value === null) return null; // SQL three-valued logic, including NOT/NOT IN.
    const difference = compare(value, values[0]);
    switch (node.op) {
      case 'equals': return difference === 0;
      case 'not:equals': return difference !== 0;
      case 'like': return String(value).toLowerCase().includes(String(values[0]).trim().toLowerCase());
      case 'not:like': return !String(value).toLowerCase().includes(String(values[0]).trim().toLowerCase());
      case 'in': return values.some(v => compare(value, v) === 0);
      case 'not:in': return !values.some(v => compare(value, v) === 0);
      case 'between': return difference >= 0 && compare(value, values[1]) <= 0;
      case 'not:between': return difference < 0 || compare(value, values[1]) > 0;
      case 'than:greater': return difference > 0;
      case 'than:less': return difference < 0;
      case 'than:equal:greater': return difference >= 0;
      case 'than:equal:less': return difference <= 0;
      default: throw Error('Unsupported local query operator');
    }
  };
}

/** Compile once per edit; evaluate against the complete supplied snapshot BEFORE table pagination. */
export function compileLocalQuery(fields: LocalFields, state: TableQueryState) {
  validateView({name: 'local', ...state}, localQuerySchema(fields)['x-query']);
  const predicates = Object.entries(state.filters).map(([field, value]) => compile(filterNode(field, value)!, fields));
  const sort = state.sort ? Array.isArray(state.sort) ? state.sort : [state.sort] : [];
  return {
    matches: (row: unknown) => predicates.every(predicate => predicate(row) === true),
    compare: (left: unknown, right: unknown) => {
      for (const item of sort) {
        const [alias, direction] = item.split(',');
        const a = fieldValue(left, alias, fields[alias]), b = fieldValue(right, alias, fields[alias]);
        if (a === null || b === null) {if (a !== b) return a === null ? 1 : -1; continue;}
        const result = compare(a, b);
        if (result) return direction === 'desc' ? -result : result;
      }
      return 0; // Stable array sort retains snapshot order for ties.
    },
  };
}

export function queryLocalRows<T>(rows: readonly T[], fields: LocalFields, state: TableQueryState): T[] {
  const query = compileLocalQuery(fields, state);
  return rows.filter(query.matches).sort(query.compare);
}
