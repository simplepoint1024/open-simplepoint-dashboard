import {formulaReferences, parseFormula} from '@simplepoint/calculation';
import type {DesignDocument, DesignField} from './contracts.ts';
import type {FormComponentDefinition} from '../FormRuntime/contracts.ts';

export type FieldTarget = {scope: string; fieldId: string};
export type AnalyzedField = FieldTarget & {key: string; label: string; field: DesignField};
export const fieldKey = (target: FieldTarget) => JSON.stringify([target.scope, target.fieldId]);
export function designFields(document: DesignDocument): AnalyzedField[] {
  return [...document.fields.map(field => ({scope: 'parent', fieldId: field.fieldId, field, label: field.label})),
    ...(document.details ?? []).flatMap(detail => detail.fields.map(field => ({scope: detail.detailId, fieldId: field.fieldId,
      field, label: `${detail.label} · ${field.label}`})))].map(item => ({...item, key: fieldKey(item)}));
}
export function fieldUsages(document: DesignDocument, target: FieldTarget) {
  const result: {componentId: string; sceneId: string; sceneName: string; label: string}[] = [];
  for (const scene of document.scenes) {
    const visit = (node: FormComponentDefinition) => {
      if (target.scope === 'parent' ? node.fieldId === target.fieldId : node.properties.detailId?.value === target.scope) {
        result.push({componentId: node.componentId, sceneId: scene.sceneId, sceneName: scene.name, label: node.label ?? ''});
      }
      node.children.forEach(visit);
    }; visit(scene.root);
  }
  return result;
}

/** Edges are keyed by identities, including parent/row/collection references. Invalid drafts remain inspectable. */
export function fieldDependencies(document: DesignDocument) {
  const fields = designFields(document), edges = new Map<string, string[]>();
  for (const owner of fields) {
    const references = new Map(fields.flatMap(target => {
      if (owner.scope === 'parent') return [[target.scope === 'parent' ? target.fieldId : `${target.scope}.${target.fieldId}`, target.key] as const];
      if (target.scope === 'parent') return [[`parent.${target.fieldId}`, target.key] as const];
      return target.scope === owner.scope ? [[target.fieldId, target.key] as const] : [];
    }));
    try {edges.set(owner.key, owner.field.calculation ? [...formulaReferences(parseFormula(owner.field.calculation.expression))]
      .flatMap(reference => references.has(reference) ? [references.get(reference)!] : []) : []);}
    catch {edges.set(owner.key, []);}
  }
  // Strongly connected components also identify intersecting cycles; a visited DFS edge alone cannot.
  const cyclic = new Set<string>(), indices = new Map<string, number>(), low = new Map<string, number>();
  const active = new Set<string>(), stack: string[] = [];
  const visit = (key: string) => {
    indices.set(key, indices.size); low.set(key, indices.get(key)!); stack.push(key); active.add(key);
    for (const next of edges.get(key) ?? []) {
      if (!indices.has(next)) {visit(next); low.set(key, Math.min(low.get(key)!, low.get(next)!));}
      else if (active.has(next)) low.set(key, Math.min(low.get(key)!, indices.get(next)!));
    }
    if (low.get(key) !== indices.get(key)) return;
    const group: string[] = []; let item: string;
    do {item = stack.pop()!; active.delete(item); group.push(item);} while (item !== key);
    if (group.length > 1 || edges.get(key)?.includes(key)) group.forEach(member => cyclic.add(member));
  };
  fields.forEach(field => {if (!indices.has(field.key)) visit(field.key);});
  return {fields, edges, cyclic};
}
export function affectedFields(document: DesignDocument, target: FieldTarget) {
  const graph = fieldDependencies(document), changed = new Set([fieldKey(target)]);
  let size: number;
  do {
    size = changed.size;
    for (const [key, inputs] of graph.edges) if (inputs.some(input => changed.has(input))) changed.add(key);
  } while (changed.size !== size);
  changed.delete(fieldKey(target));
  return graph.fields.filter(field => changed.has(field.key));
}

/** Project a local edit into a complete trial/validation document without mutating either source. */
export function replaceDesignField(document: DesignDocument, scope: string, field: DesignField): DesignDocument {
  const replace = (fields: DesignField[]) => fields.some(item => item.fieldId === field.fieldId)
    ? fields.map(item => item.fieldId === field.fieldId ? field : item) : [field, ...fields];
  if (scope === 'parent') return {...document, fields: replace(document.fields)};
  if (!document.details?.some(detail => detail.detailId === scope)) throw new Error('Unknown detail scope');
  return {...document, details: document.details.map(detail => detail.detailId === scope ? {...detail, fields: replace(detail.fields)} : detail)};
}
