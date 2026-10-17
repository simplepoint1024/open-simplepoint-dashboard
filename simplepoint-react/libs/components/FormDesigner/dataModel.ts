import {businessPattern} from '../../shared/validation/businessPattern.ts';
import {normalizedValidation, textField, validateFieldRules} from './fieldValidation.ts';
import type {ComponentDescriptor, DesignDocument, DesignField} from './contracts.ts';
import type {FormCondition, FormFieldType, FormFieldWritePolicy} from '../FormRuntime/contracts.ts';
import {normalizeFormLiteral, fieldValueKind} from '../FormRuntime/literals.ts';
import {exactDecimal} from '../../shared/utils/decimal.ts';
import {sameDesignValue} from './draftState.ts';
import {validateStructure} from './model.ts';

export type DesignIndex = DesignDocument['indexes'][number];
export type FieldEdit = Pick<DesignField, 'validation' | 'label' | 'type' | 'length' | 'precision' | 'scale' | 'submitRequired' | 'storageRequired' | 'choices'> & {
  writePolicy: FormFieldWritePolicy;
  defaultMode: 'NONE' | 'NULL' | 'VALUE';
  defaultText: string;
};
export class DataEditError extends Error {
  readonly code: 'conflict' | 'fieldType' | 'constraints' | 'defaultValue' | 'choices' | 'indexFields' | 'limit';
  constructor(code: DataEditError['code']) {super(code); this.code = code;}
}
export const fieldTypes: readonly FormFieldType[] = ['STRING', 'TEXT', 'BOOLEAN', 'INT32', 'INT64', 'DECIMAL', 'DATE', 'TIME', 'DATETIME', 'UUID', 'JSON'];
export const persistentField = (field: DesignField) => ['STORED', 'REFERENCE', 'ATTACHMENT'].includes(field.storageMode);

/** A shared field must remain compatible with every scene and existing typed rule. */
export function allowedFieldTypes(document: DesignDocument, field: DesignField, components: readonly ComponentDescriptor[]) {
  let allowed = fieldTypes.filter(type => field.storageMode !== 'REFERENCE' || type === 'STRING' || type === 'UUID')
    .filter(type => field.storageMode !== 'ATTACHMENT' || type === 'JSON');
  const visit = (node: DesignDocument['scenes'][number]['root']) => {
    if (node.fieldId === field.fieldId) {
      const descriptor = components.find(item => item.type === node.componentType && item.version === node.componentVersion);
      allowed = allowed.filter(type => descriptor?.supportedFieldTypes.includes(type));
    }
    node.children.forEach(visit);
  };
  document.scenes.forEach(scene => visit(scene.root));
  const references = (condition: FormCondition): boolean => condition.fieldId === field.fieldId || condition.children.some(references);
  if (document.rules.some(rule => references(rule.condition))) allowed = allowed.filter(type => type === field.type);
  return allowed;
}

export function fieldEdit(field: DesignField): FieldEdit {
  return {validation: field.validation, label: field.label, type: field.type, length: field.length, precision: field.precision, scale: field.scale,
    submitRequired: field.submitRequired, storageRequired: field.storageRequired, choices: structuredClone(field.choices),
    writePolicy: field.writePolicy ?? 'EDITABLE', defaultMode: field.defaultValue == null ? 'NONE'
      : field.defaultValue.kind === 'NULL' ? 'NULL' : 'VALUE', defaultText: field.defaultValue?.value ?? ''};
}

/** Type changes reset only irrelevant modifiers; literals stay visible until the user corrects them. */
export function changeFieldType(edit: FieldEdit, type: FormFieldType): FieldEdit {
  if (edit.type === type) return edit;
  return {...edit, type, validation: textField(type) ? edit.validation : undefined, length: type === 'STRING' ? 255 : null,
    precision: type === 'DECIMAL' ? 18 : null, scale: type === 'DECIMAL' ? 2 : null};
}

function literal(field: DesignField, value: string): string {
  const type = field.type, result = normalizeFormLiteral(type, value);
  if (textField(type)) {
    const size = Array.from(result).length, rule = field.validation;
    if (type === 'STRING' && size > field.length!) throw Error('Length exceeded');
    if (result && (rule?.minLength != null && size < rule.minLength
      || rule?.maxLength != null && size > rule.maxLength)) throw Error('Invalid length');
    if (result && rule?.pattern && !businessPattern(rule.pattern).matcher(result).find()) throw Error('Invalid pattern');
  }
  if (type === 'DECIMAL') {
    const {coefficient, scale} = exactDecimal(result), targetScale = field.scale!;
    const digits = (coefficient < 0n ? -coefficient : coefficient).toString().length;
    if (scale > targetScale || coefficient !== 0n && digits + targetScale - scale > field.precision!) throw Error('Precision exceeded');
  }
  return result;
}

function literalIdentity(type: FormFieldType, value: string): string {
  if (type === 'UUID') return value.toLowerCase();
  if (type === 'TIME') {
    const [clock, fraction = ''] = value.split('.');
    return `${clock.length === 5 ? clock + ':00' : clock}.${fraction.replace(/0+$/, '')}`;
  }
  if (type === 'DATETIME') {
    const fraction = /\.(\d+)/.exec(value)?.[1] ?? '';
    return `${Date.parse(value.replace(/\.\d+/, ''))}.${fraction.replace(/0+$/, '')}`;
  }
  return value;
}

function validateField(field: DesignField) {
  const integer = (value: unknown, min: number, max: number) => typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max;
  if (!field.label?.trim() || field.label.length > 120
    || field.type === 'STRING' && !integer(field.length, 1, 65535)
    || field.type === 'DECIMAL' && (!integer(field.precision, 1, 38) || !integer(field.scale, 0, field.precision!))) {
    throw new DataEditError('constraints');
  }
  try {validateFieldRules(field.validation, field.length ?? undefined);} catch {throw new DataEditError('constraints');}
  if (field.storageRequired && (!persistentField(field) || !field.defaultValue || field.defaultValue.kind === 'NULL')) {
    throw new DataEditError('defaultValue');
  }
  let defaultText: string | undefined;
  if (field.defaultValue && field.defaultValue.kind !== 'NULL') {
    try {
      defaultText = literal(field, field.defaultValue.value!);
      field.defaultValue = {...field.defaultValue, value: defaultText};
    } catch {throw new DataEditError('defaultValue');}
  }
  if (field.choices.length > 200) throw new DataEditError('limit');
  const values = new Set<string>();
  for (const choice of field.choices) {
    try {
      if (!choice.value?.trim() || choice.value.length > 256 || !choice.label?.trim() || choice.label.length > 120 || field.type === 'JSON') throw Error();
      const canonical = literalIdentity(field.type, literal(field, choice.value));
      if (values.has(canonical)) throw Error();
      values.add(canonical);
    } catch {throw new DataEditError('choices');}
  }
  if (values.size && defaultText !== undefined && !values.has(literalIdentity(field.type, defaultText))) throw new DataEditError('defaultValue');
}

/** Merge only this field into the latest document, preserving storage identity and unrelated edits. */
export function applyFieldEdit(document: DesignDocument, base: DesignField, edit: FieldEdit,
  components: readonly ComponentDescriptor[], writePolicies: readonly FormFieldWritePolicy[], creating = false, scope: 'form' | 'detail' = 'form'): DesignDocument {
  const current = document.fields.find(field => field.fieldId === base.fieldId);
  if (creating ? current || document.fields.some(field => field.storageCode === base.storageCode) : !current || !sameDesignValue(current, base)) {
    throw new DataEditError('conflict');
  }
  if (!allowedFieldTypes(document, base, components).includes(edit.type)) throw new DataEditError('fieldType');
  if (edit.writePolicy !== (base.writePolicy ?? 'EDITABLE') && (!persistentField(base) || !writePolicies.includes(edit.writePolicy))) {
    throw new DataEditError('constraints');
  }
  const field: DesignField = {...base, validation: textField(edit.type) ? normalizedValidation(edit.validation) : undefined, label: edit.label, type: edit.type,
    length: edit.type === 'STRING' ? edit.length : null, precision: edit.type === 'DECIMAL' ? edit.precision : null,
    scale: edit.type === 'DECIMAL' ? edit.scale : null, submitRequired: edit.submitRequired, storageRequired: edit.storageRequired,
    choices: structuredClone(edit.choices), defaultValue: edit.defaultMode === 'NONE' ? null
      : edit.defaultMode === 'NULL' ? {kind: 'NULL'} : {kind: fieldValueKind(edit.type), value: edit.defaultText}};
  if (creating) field.storageLabel = edit.label;
  if (edit.writePolicy === 'EDITABLE') delete field.writePolicy;
  else field.writePolicy = edit.writePolicy;
  validateField(field);
  const next = {...document, fields: creating ? [field, ...document.fields]
    : document.fields.map(item => item.fieldId === field.fieldId ? field : item)};
  if (scope === 'form') validateStructure(next, components);
  else if (next.fields.length > 48) throw new DataEditError('limit');
  return next;
}

export function newIndex(): DesignIndex {
  const indexId = crypto.randomUUID();
  return {indexId, storageCode: `idx_${indexId.replace(/-/g, '')}`, unique: false, fieldIds: []};
}

/** Index field order is significant; storage identity is owned by the editor, never the input form. */
export function applyIndexEdit(document: DesignDocument, base: DesignIndex, edit: Pick<DesignIndex, 'unique' | 'fieldIds'>,
  creating = false): DesignDocument {
  const current = document.indexes.find(index => index.indexId === base.indexId);
  if (creating ? current || document.indexes.some(index => index.storageCode === base.storageCode) : !current || !sameDesignValue(current, base)) {
    throw new DataEditError('conflict');
  }
  const fields = new Set(document.fields.filter(persistentField).map(field => field.fieldId));
  if (!edit.fieldIds.length || edit.fieldIds.length > 8 || new Set(edit.fieldIds).size !== edit.fieldIds.length
    || edit.fieldIds.some(id => !fields.has(id))) throw new DataEditError('indexFields');
  if (creating && document.indexes.length >= 50) throw new DataEditError('limit');
  const updated = {...base, unique: edit.unique, fieldIds: [...edit.fieldIds]};
  return {...document, indexes: creating ? [updated, ...document.indexes]
    : document.indexes.map(index => index.indexId === base.indexId ? updated : index)};
}

export function removeIndex(document: DesignDocument, base: DesignIndex): DesignDocument {
  if (!sameDesignValue(document.indexes.find(index => index.indexId === base.indexId), base)) throw new DataEditError('conflict');
  return {...document, indexes: document.indexes.filter(index => index.indexId !== base.indexId)};
}
