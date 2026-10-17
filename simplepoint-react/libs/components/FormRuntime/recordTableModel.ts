import {detailPayload} from '../FormComponents/Detail/payload.ts';
import {detailSchema} from '../FormComponents/Detail/schema.ts';
import {filterNode, readDocument, type QueryNode} from '../Table/query.ts';
import type {SimpleTablePageRequest} from '../SimpleTable/types.ts';
import type {Page} from '@simplepoint/shared/types/request';
import type {ApplicationFormRuntimeView} from './contracts.ts';
import {fieldNullability} from './fieldNullability.ts';
import {fieldWriteAllowed} from './writePolicy.ts';

export type RecordState = 'DRAFT' | 'SUBMITTED';
export type FormRecord = {
  id: string;
  version: number;
  state: RecordState;
  values: Record<string, unknown>;
  systemValues?: Record<string, unknown>;
};
export type FormRecordMutation = {
  recordId: string;
  version: number;
  state: RecordState;
  currentRecord: FormRecord | null;
};
export type FormRecordPage = {
  page: number;
  size: number;
  total: number;
  records: FormRecord[];
};
export type RecordRow = Record<string, unknown> & {
  id: string;
  version: number;
  recordState: RecordState;
};

type RecordFilter = {
  operator: string;
  fieldId?: string;
  values: unknown[];
  children: RecordFilter[];
};

const operators: Record<string, string> = {
  and: 'AND',
  or: 'OR',
  not: 'NOT',
  equals: 'EQUALS',
  'not:equals': 'NOT_EQUALS',
  in: 'IN',
  'not:in': 'NOT_IN',
  like: 'LIKE',
  'not:like': 'NOT_LIKE',
  'than:greater': 'GREATER_THAN',
  'than:less': 'LESS_THAN',
  'than:equal:greater': 'GREATER_THAN_OR_EQUAL',
  'than:equal:less': 'LESS_THAN_OR_EQUAL',
  between: 'BETWEEN',
  'not:between': 'NOT_BETWEEN',
  'is:null': 'IS_NULL',
  'is:not:null': 'IS_NOT_NULL',
};

export function recordPage(page: FormRecordPage): Page<RecordRow> {
  return {
    content: page.records.map(record => ({
      ...record.values,
      ...record.systemValues,
      id: record.id,
      version: record.version,
      recordState: record.state,
    })),
    page: {
      size: page.size,
      totalElements: page.total,
      totalPages: page.size > 0 ? Math.ceil(page.total / page.size) : 0,
      number: page.page,
    },
  };
}

export function recordPageQuery(request: SimpleTablePageRequest) {
  const nodes = Object.entries(request.filters).map(([field, value]) => {
    const node = field === '$query' ? readDocument(value) : filterNode(field, value);
    if (!node) throw new Error('Invalid record table filter');
    return node;
  });
  const filter = nodes.length === 0
    ? null
    : compileFilter(nodes.length === 1 ? nodes[0] : {op: 'and', children: nodes});
  const sorts = (request.sort == null
    ? [] : Array.isArray(request.sort) ? request.sort : [request.sort]
  ).map(item => {
    const [fieldId, direction, ...remaining] = item.split(',');
    if (!fieldId || remaining.length || !['asc', 'desc'].includes(direction)) {
      throw new Error('Invalid record table sort');
    }
    return {fieldId, direction: direction.toUpperCase()};
  });
  return {
    page: request.page,
    size: request.size,
    fieldIds: [],
    filter,
    sorts,
  };
}

export function editableValues(
  formData: Record<string, unknown>,
  editableFieldIds: ReadonlySet<string>,
  current?: RecordRow | null,
) {
  const values: Record<string, unknown> = {};
  editableFieldIds.forEach(fieldId => {
    const value = formData[fieldId];
    if (value === undefined && (!current || current[fieldId] == null)) return;
    const normalized = value === undefined ? null : value;
    if (current && Object.is(normalized, current[fieldId])) return;
    values[fieldId] = normalized;
  });
  return values;
}

/** A complete form snapshot may clear only fields owned by its editable scene. */
export function editableFieldIds(descriptor: ApplicationFormRuntimeView, sceneId?: string, recordState?: RecordState) {
  const result = new Set<string>();
  const fields = new Map(descriptor.document.fields.map(field => [field.fieldId, field]));
  const visit = (component: ApplicationFormRuntimeView['document']['scenes'][number]['root'], parentReadOnly = false) => {
    const readOnly = parentReadOnly || component.readOnly;
    const field = component.fieldId ? fields.get(component.fieldId) : undefined;
    if (component.componentType === 'data.detail' && !readOnly && component.properties.detailId?.value) result.add(component.properties.detailId.value);
    if (field && !readOnly && fieldWriteAllowed(field, recordState)) result.add(field.fieldId);
    component.children.forEach(child => visit(child, readOnly));
  };
  descriptor.document.scenes.filter(scene => !sceneId || scene.sceneId === sceneId)
    .forEach(scene => visit(scene.root));
  return result;
}

export function runtimeIdentity(descriptor: ApplicationFormRuntimeView) {
  return {formId: descriptor.formId, formVersion: descriptor.formVersion};
}

/** Pin effective restrictions; another page's layout-only publication must not discard local work. */
export function runtimeDescriptorKey(descriptor: ApplicationFormRuntimeView): string {
  const restrictions = descriptor.document.fields
    .filter(field => field.writePolicy && field.writePolicy !== 'EDITABLE')
    .map(field => [field.fieldId, field.writePolicy])
    .sort(([left], [right]) => left! < right! ? -1 : left === right ? 0 : 1);
  return JSON.stringify([descriptor.formId, descriptor.formVersion, restrictions]);
}

/** Adapts a platform table form using its immutable editing record, including fields in secondary scenes. */
export function recordEditorSchema(schema: any, descriptor: ApplicationFormRuntimeView, current?: RecordRow | null) {
  const fields = new Map(descriptor.document.fields.map(field => [field.fieldId, field]));
  const properties: Record<string, any> = Object.fromEntries(Object.entries(schema?.properties ?? {})
    .filter(([key]) => !['id', 'version', 'recordState'].includes(key))
    .map(([key, property]) => {
      const field = fields.get(key);
      return [key, {...property as Record<string, unknown>,
        ...(!field || !fieldWriteAllowed(field, current?.recordState) || (property as any).readOnly
          ? {readOnly: true, 'x-display-only': true} : {})}];
    }));
  for (const detail of descriptor.document.details ?? []) {
    properties[detail.detailId] = detailSchema(detail, current?.recordState, false);
  }
  const required = current ? [] : descriptor.document.fields
    .filter(field => field.writePolicy === 'CREATE_ONLY' && field.submitRequired && properties[field.fieldId])
    .map(field => field.fieldId);
  required.forEach(fieldId => {properties[fieldId] = fieldNullability(properties[fieldId], false);});
  return {...schema, properties, required};
}

/** Saving a draft retains its identity; submitting it must update that same record. */
export function recordMutation(
  descriptor: ApplicationFormRuntimeView,
  current: FormRecord | undefined,
  formData: Record<string, unknown>,
  state: RecordState,
  idempotencyKey: string,
) {
  if (current?.state === 'SUBMITTED' && state === 'DRAFT') {
    throw new Error('A submitted record cannot return to draft state');
  }
  const values = detailPayload(descriptor.document, editableValues(formData, editableFieldIds(descriptor, undefined, current?.state),
    current ? {...current.values, id: current.id, version: current.version, recordState: current.state} : undefined), current?.state);
  const runtime = runtimeIdentity(descriptor);
  return current
    ? {method: 'PUT' as const, recordId: current.id,
      body: {runtime, idempotencyKey, expectedVersion: current.version, state, values}}
    : {method: 'POST' as const, recordId: undefined,
      body: {runtime, idempotencyKey, state, values}};
}

export function createIdempotencyKey(
  previous: {fingerprint: string; key: string} | undefined,
  state: RecordState,
  values: Record<string, unknown>,
) {
  const fingerprint = stableJson({state, values});
  return previous?.fingerprint === fingerprint
    ? previous : {fingerprint, key: globalThis.crypto.randomUUID()};
}

function compileFilter(node: QueryNode): RecordFilter {
  const operator = operators[node.op];
  if (!operator) throw new Error('Unsupported record table filter');
  if (node.children) {
    return {
      operator,
      values: [],
      children: node.children.map(compileFilter),
    };
  }
  const values = node.value === undefined
    ? [] : Array.isArray(node.value) ? node.value : [node.value];
  return {
    operator,
    fieldId: node.field,
    values,
    children: [],
  };
}

function stableJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.entries(value as Record<string, unknown>)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, item]) => `${JSON.stringify(key)}:${stableJson(item)}`)
      .join(',')}}`;
  }
  return JSON.stringify(value) ?? 'null';
}
