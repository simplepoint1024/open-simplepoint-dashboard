import type {RJSFSchema} from '@rjsf/utils';
import type {MetadataPathSegment} from '../metadataTree';

export type FormKind = 'namespace' | 'table' | 'view' | 'columnAdd' | 'columnAlter' | 'constraint' | 'drop' | 'dropColumn' | 'dropConstraint';
export type MetadataDraft = Record<string, any>;
export type DialogTarget = {kind: FormKind; path: MetadataPathSegment[]; data: MetadataDraft;
  currentName?: string; constraintType?: string};
export type Translate = (key: string, fallback?: string) => string;
export const constraintTypes = ['PRIMARY_KEY', 'UNIQUE', 'FOREIGN_KEY', 'CHECK'] as const;
export function constraintTypeTitle(t: Translate, type: string): string {
  return ({
    PRIMARY_KEY: t('dna.metadata.page.constraintType.PRIMARY_KEY', 'Primary Key'),
    UNIQUE: t('dna.metadata.page.constraintType.UNIQUE', 'Unique'),
    FOREIGN_KEY: t('dna.metadata.page.constraintType.FOREIGN_KEY', 'Foreign Key'),
    CHECK: t('dna.metadata.page.constraintType.CHECK', 'CHECK'),
  } as Record<string, string>)[type] ?? type;
}
const text = (title: string, maxLength = 128): RJSFSchema => ({type: 'string', title, minLength: 1, pattern: '\\S', maxLength});
const optionalText = (title: string, maxLength = 512): RJSFSchema => ({type: ['string', 'null'], title, maxLength});
const object = (properties: Record<string, RJSFSchema>, required: string[] = []): RJSFSchema =>
  ({type: 'object', properties, required, additionalProperties: false});

export function columnSchema(t: Translate): RJSFSchema {
  return object({
    name: text(t('dna.metadata.page.form.column.name.label', 'Column Name')),
    typeName: text(t('dna.metadata.page.form.column.typeName.label', 'Type')),
    size: {type: ['integer', 'null'], minimum: 1, maximum: 2147483647, title: t('dna.metadata.page.form.column.size.label', 'Length / Precision')},
    scale: {type: ['integer', 'null'], minimum: 0, maximum: 2147483647, title: t('dna.metadata.page.form.column.scale.label', 'Scale')},
    nullable: {type: 'boolean', title: t('dna.metadata.page.form.column.nullable.label', 'Nullable')},
    defaultValue: optionalText(t('dna.metadata.page.form.column.defaultValue.label', 'Default Value'), 256),
    autoIncrement: {type: 'boolean', title: t('dna.metadata.page.form.column.autoIncrement.label', 'Auto Increment')},
    remarks: optionalText(t('dna.metadata.page.form.column.remarks.label', 'Remarks')),
  }, ['name', 'typeName']);
}

export function constraintSchema(t: Translate, columns?: string[]): RJSFSchema {
  const strings = (title: string, known?: string[]): RJSFSchema => ({type: 'array', title,
    uniqueItems: true, items: known?.length ? {type: 'string', enum: known} : text(title)});
  const reference = object({
    tablePath: {type: 'array', minItems: 1, title: t('dna.metadata.page.form.constraint.referenceTable.label', 'Reference Table'),
      items: object({type: {type: 'string', enum: ['DATABASE', 'CATALOG', 'SCHEMA', 'TABLE']}, name: text(t('dna.metadata.page.detail.name', 'Name'))}, ['type', 'name'])},
    columns: {...strings(t('dna.metadata.page.table.constraints.columns', 'Columns')), minItems: 1},
  }, ['tablePath', 'columns']);
  const schema = object({
    name: optionalText(t('dna.metadata.page.form.constraint.name.label', 'Constraint Name'), 128),
    type: {type: 'string', oneOf: constraintTypes.map(type => ({const: type, title: constraintTypeTitle(t, type)}))},
    // Inactive fields retain draft values, but only the selected constraint type validates and sends them.
    // Defining reference array minItems outside the FK branch makes RJSF synthesize invalid null items for CHECK.
    columns: {type: 'array', title: t('dna.metadata.page.table.constraints.columns', 'Columns')},
    reference: {type: ['object', 'null'], default: null,
      title: t('dna.metadata.page.table.constraints.reference', 'Reference')},
    checkExpression: optionalText(t('dna.metadata.page.form.constraint.checkExpression.label', 'CHECK Expression'), 16000),
  }, ['type']);
  schema.allOf = [
    {if: {properties: {type: {const: 'CHECK'}}, required: ['type']}, then: {
      required: ['checkExpression'], properties: {checkExpression: text(t('dna.metadata.page.form.constraint.checkExpression.label', 'CHECK Expression'), 16000)}},
      else: {required: ['columns'], properties: {columns: {
        ...strings(t('dna.metadata.page.table.constraints.columns', 'Columns'), columns), minItems: 1}}}},
    {if: {properties: {type: {const: 'FOREIGN_KEY'}}, required: ['type']},
      then: {required: ['reference'], properties: {reference}}},
  ];
  return schema;
}

export function metadataFormSchema(kind: FormKind, t: Translate, namespaceTypes: string[], columns: string[]): RJSFSchema {
  switch (kind) {
    case 'namespace': return object({type: {type: 'string', oneOf: namespaceTypes.map(type => ({const: type,
      title: t(`dna.metadata.page.option.namespace.${type}`, type)}))},
      name: text(t('dna.metadata.page.form.namespace.name.label', 'Name'))}, ['type', 'name']);
    case 'table': return object({name: text(t('dna.metadata.page.form.table.name.label', 'Table Name')),
      columns: {type: 'array', minItems: 1, title: t('dna.metadata.page.table.constraints.columns', 'Columns'), items: columnSchema(t)},
      constraints: {type: 'array', title: t('dna.metadata.page.table.constraints.name', 'Constraints'), items: constraintSchema(t)}}, ['name', 'columns']);
    case 'view': return object({name: text(t('dna.metadata.page.form.view.name.label', 'View Name')),
      definitionSql: {...text(t('dna.metadata.page.form.view.definitionSql.label', 'View SQL'), 100000), 'x-ui': {widget: 'Sql'}}}, ['name', 'definitionSql']);
    case 'columnAdd': case 'columnAlter': return columnSchema(t);
    case 'constraint': return constraintSchema(t, columns);
    case 'drop': return object({cascade: {type: 'boolean', default: false,
      title: t('dna.metadata.page.delete.cascade', 'Also delete dependent objects (CASCADE)')}});
    default: return object({});
  }
}

/** Identifiers remain array values, including quoted names containing commas. */
export function metadataPayload(target: DialogTarget, data: MetadataDraft): {suffix: string; payload: MetadataDraft} {
  switch (target.kind) {
    case 'namespace': return {suffix: 'namespaces', payload: {parentPath: target.path, type: data.type, name: data.name?.trim()}};
    case 'table': return {suffix: 'tables', payload: {parentPath: target.path, name: data.name?.trim(),
      columns: data.columns, constraints: (data.constraints ?? []).map(normalizeConstraint)}};
    case 'view': return {suffix: 'views', payload: {parentPath: target.path, name: data.name?.trim(), definitionSql: data.definitionSql?.trim()}};
    case 'columnAdd': case 'columnAlter': return {suffix: target.kind === 'columnAdd' ? 'columns' : 'columns/alter',
      payload: {tablePath: target.path, ...(target.kind === 'columnAlter' ? {currentName: target.currentName} : {}), column: data}};
    case 'constraint': return {suffix: 'constraints', payload: {tablePath: target.path, constraint: normalizeConstraint(data)}};
    case 'drop': return {suffix: 'drop', payload: {path: target.path, cascade: data.cascade === true}};
    case 'dropColumn': return {suffix: 'columns/drop', payload: {tablePath: target.path, columnName: target.currentName}};
    case 'dropConstraint': return {suffix: 'constraints/drop', payload: {tablePath: target.path,
      constraintName: target.currentName, type: target.constraintType}};
  }
}
function normalizeConstraint(data: MetadataDraft) {
  return {name: data.name?.trim() || null, type: data.type,
    columns: data.type === 'CHECK' ? [] : data.columns ?? [],
    reference: data.type === 'FOREIGN_KEY' ? data.reference : null,
    checkExpression: data.type === 'CHECK' ? data.checkExpression?.trim() : null};
}

export function validateMetadataDraft(kind: FormKind, data: MetadataDraft): void {
  const constraints = kind === 'table' ? data.constraints ?? [] : kind === 'constraint' ? [data] : [];
  const columns: MetadataDraft[] = kind === 'table' ? data.columns ?? [] : kind === 'columnAdd' || kind === 'columnAlter' ? [data] : [];
  if (new Set(columns.map(column => column.name)).size !== columns.length) throw Error('DUPLICATE_COLUMNS');
  for (const column of columns) {
    if (column.size != null && column.scale != null && column.scale > column.size) throw Error('INVALID_SCALE');
  }
  for (const constraint of constraints) {
    if (constraint.type === 'FOREIGN_KEY' && constraint.columns?.length !== constraint.reference?.columns?.length) throw Error('FOREIGN_KEY_ARITY');
    if (constraint.type === 'FOREIGN_KEY' && constraint.reference?.tablePath?.at(-1)?.type !== 'TABLE') throw Error('FOREIGN_KEY_TABLE');
    if (kind === 'table' && constraint.type !== 'CHECK' && constraint.columns?.some((name: string) => !columns.some(column => column.name === name))) throw Error('UNKNOWN_COLUMN');
  }
}
