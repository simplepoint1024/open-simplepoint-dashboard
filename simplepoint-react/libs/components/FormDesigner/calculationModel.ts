import {node, parseFormula, printFormula, type FormulaField, type FormulaNode} from '@simplepoint/calculation';
import type {DesignDocument, DesignField} from './contracts.ts';
import {persistentField} from './fieldCapabilities.ts';

/** References use stable IDs. Labels and physical column names are presentation only. */
export function calculationFields(document: DesignDocument, scope: string, ownField: string): FormulaField[] {
  const map = (fields: DesignField[], prefix: string, group: string, exclude?: string) => fields
    .filter(field => persistentField(field) && field.fieldId !== exclude)
    .map(field => ({id: prefix + field.fieldId, label: field.label, type: field.type, group}));
  if (scope === 'parent') return [
    ...map(document.fields, '', '', ownField),
    ...(document.details ?? []).flatMap(detail => map(detail.fields, detail.detailId + '.', detail.label)),
  ];
  const detail = document.details?.find(item => item.detailId === scope);
  return [...map(detail?.fields ?? [], '', detail?.label ?? '', ownField), ...map(document.fields, 'parent.', 'parent')];
}

/** AST rewriting cannot accidentally rename a string literal that happens to contain a field ID. */
export function remapCalculation(field: DesignField, identities: ReadonlyMap<string, string>): DesignField {
  if (!field.calculation) return field;
  const remap = (value: FormulaNode): FormulaNode => node(value.kind,
    value.kind === 'FIELD' ? identities.get(value.text) ?? value.text : value.text, value.children.map(remap));
  return {...field, calculation: {...field.calculation, expression: printFormula(remap(parseFormula(field.calculation.expression)))}};
}

/** Designer metadata stays in the draft. Preview only adds the same capability marker as the server runtime. */
export function calculationPreviewDocument(document: DesignDocument) {
  const project = (field: DesignField) => field.calculation ? {...field, calculated: true} : field;
  return {...document, fields: document.fields.map(project), details: document.details?.map(detail => ({...detail, fields: detail.fields.map(project)}))};
}
