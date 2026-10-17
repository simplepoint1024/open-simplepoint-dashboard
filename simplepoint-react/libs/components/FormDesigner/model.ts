import {platformComponentBehaviors} from '../FormComponents/modelRegistry.ts';
import type {FormComponentDefinition as Node, FormSceneDefinition as Scene, FormFieldType} from '../FormRuntime/contracts.ts';
import type {ComponentDescriptor, DesignDocument, DesignField} from './contracts.ts';
import {sameDesignDocument} from './draftState.ts';

export type NodeLocation = {node: Node; scene: Scene; parent?: Node; index: number};
export type Clipboard = {node: Node; fields: DesignField[]; rules: DesignDocument['rules']; details?: DesignDocument['details']};
export type DesignHistory = {past: DesignDocument[]; present: DesignDocument; future: DesignDocument[]};
export type Identity = () => string;
export class DesignError extends Error {
  readonly code: 'parent' | 'field' | 'cycle' | 'limit' | 'referenced' | 'unsupported';
  constructor(code: DesignError['code']) {super(code); this.code = code;}
}
const identity: Identity = () => crypto.randomUUID();
const descriptor = (node: Node, components: readonly ComponentDescriptor[]) => {
  const result = components.find(item => item.type === node.componentType && item.version === node.componentVersion);
  if (!result) throw new DesignError('unsupported');
  return result;
};
export function locate(document: DesignDocument, componentId: string): NodeLocation | undefined {
  const visit = (node: Node, scene: Scene, parent?: Node, index = 0): NodeLocation | undefined =>
    node.componentId === componentId ? {node, scene, parent, index}
      : node.children.map((child, i) => visit(child, scene, node, i)).find(Boolean);
  return document.scenes.map(scene => visit(scene.root, scene)).find(Boolean);
}
function walk(node: Node, visit: (node: Node) => void) {visit(node); node.children.forEach(child => walk(child, visit));}
function code(prefix: string, used: readonly (string | null | undefined)[]) {
  let index = 1; while (used.includes(`${prefix}_${index}`)) index++;
  return `${prefix}_${index}`;
}
function node(type: string, id: Identity, version = '1'): Node {
  return {componentId: id(), componentType: type, componentVersion: version, columnSpan: 24,
    readOnly: false, properties: {}, children: []};
}
function requireParent(document: DesignDocument, parentId: string, child: Node, components: readonly ComponentDescriptor[]) {
  const parent = locate(document, parentId);
  if (!parent) throw new DesignError('parent');
  const container = descriptor(parent.node, components), item = descriptor(child, components);
  if (!container.container || container.allowedChildTypes.length && !container.allowedChildTypes.includes(item.type)
    || item.allowedParentTypes.length && !item.allowedParentTypes.includes(container.type)) throw new DesignError('parent');
  return parent;
}
/** Validate command invariants before replacing a complete immutable document. Server validates all values again. */
export function validateStructure(document: DesignDocument, components: readonly ComponentDescriptor[]) {
  if (document.contractVersion !== '1') throw new DesignError('unsupported');
  if (document.fields.length > 300 || document.scenes.length > 50 || document.rules.length > 500
    || document.scenes.filter(scene => scene.type === 'MAIN').length !== 1) throw new DesignError('limit');
  const ids = new Set<string>(), fieldIds = new Set(document.fields.map(field => field.fieldId));
  const graph = new Map<string, string[]>();
  for (const scene of document.scenes) {
    const usedFields = new Set<string>(), targets: string[] = [];
    if (!descriptor(scene.root, components).container) throw new DesignError('parent');
    const visit = (item: Node, depth: number, parent?: Node) => {
      if (ids.has(item.componentId) || depth > 12 || ids.size >= 1000) throw new DesignError('limit');
      ids.add(item.componentId);
      const registered = descriptor(item, components);
      try {platformComponentBehaviors.get(item)?.validate?.(item, document);} catch {throw new DesignError('parent');}
      if (parent) requireParent(document, parent.componentId, item, components);
      if (!registered.container && item.children.length) throw new DesignError('parent');
      if (registered.fieldRequired) {
        const field = document.fields.find(field => field.fieldId === item.fieldId);
        if (!field || !fieldIds.has(field.fieldId) || usedFields.has(field.fieldId)
          || !registered.supportedFieldTypes.includes(field.type)) throw new DesignError('field');
        usedFields.add(field.fieldId);
      }
      if (item.componentType === 'action.open-dialog' || item.componentType === 'action.open-drawer') {
        const target = document.scenes.find(candidate => candidate.sceneId === item.targetSceneId);
        if (!target || target.type !== (item.componentType === 'action.open-dialog' ? 'DIALOG' : 'DRAWER'))
          throw new DesignError('referenced');
        targets.push(target.sceneId);
      }
      item.children.forEach(child => visit(child, depth + 1, item));
    };
    visit(scene.root, 1); graph.set(scene.sceneId, targets);
  }
  const memo = new Map<string, number>(), visiting = new Set<string>();
  const depth = (id: string): number => {
    if (visiting.has(id)) throw new DesignError('cycle');
    if (memo.has(id)) return memo.get(id)!;
    visiting.add(id);
    const result = 1 + Math.max(0, ...(graph.get(id) ?? []).map(depth));
    visiting.delete(id); memo.set(id, result);
    if (result > 8) throw new DesignError('limit');
    return result;
  };
  for (const id of graph.keys()) depth(id);
}
function finish(document: DesignDocument, components: readonly ComponentDescriptor[]) {
  validateStructure(document, components); return document;
}
export function addScene(document: DesignDocument, type: 'DIALOG' | 'DRAWER', name: string,
  components: readonly ComponentDescriptor[], id: Identity = identity) {
  const next = structuredClone(document), root = components.find(item => item.type === 'layout.form' && item.version === '1');
  if (!root) throw new DesignError('unsupported');
  const scene: Scene = {sceneId: id(), code: code('scene', next.scenes.map(scene => scene.code)), name,
    type, width: type === 'DIALOG' ? 720 : 480, root: node(root.type, id, root.version)};
  next.scenes.push(scene); return {document: finish(next, components), sceneId: scene.sceneId};
}
const preferredTypes: FormFieldType[] = ['STRING', 'DECIMAL', 'BOOLEAN', 'INT32', 'INT64', 'TEXT', 'DATE', 'TIME', 'DATETIME', 'UUID', 'JSON'];
export function newDataField(document: DesignDocument, type: FormFieldType, label: string, id: Identity = identity): DesignField {
  return {fieldId: id(), storageCode: code('field', document.fields.map(field => field.storageCode)), label,
    storageLabel: label, type, storageMode: 'STORED', submitRequired: false, storageRequired: false,
    length: type === 'STRING' ? 255 : null, precision: type === 'DECIMAL' ? 18 : null,
    scale: type === 'DECIMAL' ? 2 : null, defaultValue: null, choices: []};
}
function createField(document: DesignDocument, component: ComponentDescriptor, label: string, id: Identity): DesignField {
  const type = preferredTypes.find(type => component.supportedFieldTypes.includes(type));
  if (!type) throw new DesignError('field');
  return {...newDataField(document, type, label, id),
    storageMode: component.type === 'field.attachment' ? 'ATTACHMENT' : 'STORED',
    choices: component.type === 'field.select' ? [{value: 'option_1', label}] : []};
}
export function insertComponent(document: DesignDocument, parentId: string, index: number,
  component: ComponentDescriptor, label: string, components: readonly ComponentDescriptor[], existingFieldId?: string,
  id: Identity = identity) {
  let next = structuredClone(document);
  let item = node(component.type, id, component.version);
  item.label = label;
  item.properties = Object.fromEntries(Object.entries(component.propertySchema)
    .filter(([, property]) => property.defaultValue != null).map(([key, property]) => [key, structuredClone(property.defaultValue!)]));
  item = platformComponentBehaviors.get(item)?.initialize?.(item, id, next) ?? item;
  if (component.fieldRequired) {
    const field = existingFieldId ? next.fields.find(field => field.fieldId === existingFieldId)
      : createField(next, component, label, id);
    if (!field) throw new DesignError('field');
    if (!existingFieldId) next.fields.unshift(field);
    if (existingFieldId) {
      // Reusing a field preserves its existing component domain (options/rating limits).
      for (const scene of next.scenes) walk(scene.root, existing => {
        if (existing.fieldId === existingFieldId && existing.componentType === item.componentType
          && existing.componentVersion === item.componentVersion) item.properties = structuredClone(existing.properties);
      });
    }
    item.fieldId = field.fieldId;
    item.label = undefined;
  }
  if (component.type === 'action.open-dialog' || component.type === 'action.open-drawer') {
    const created = addScene(next, component.type === 'action.open-dialog' ? 'DIALOG' : 'DRAWER', label, components, id);
    next = created.document; item.targetSceneId = created.sceneId;
  }
  const parent = requireParent(next, parentId, item, components);
  parent.node.children.splice(Math.max(0, Math.min(index, parent.node.children.length)), 0, item);
  return {document: finish(next, components), componentId: item.componentId};
}
export function moveComponent(document: DesignDocument, componentId: string, parentId: string,
  index: number, components: readonly ComponentDescriptor[]) {
  const next = structuredClone(document), source = locate(next, componentId);
  if (!source?.parent || platformComponentBehaviors.get(source.node)?.structural) throw new DesignError('parent');
  const descendantIds = new Set<string>(); walk(source.node, item => descendantIds.add(item.componentId));
  if (descendantIds.has(parentId)) throw new DesignError('cycle');
  const parent = requireParent(next, parentId, source.node, components);
  source.parent.children.splice(source.index, 1);
  const offset = source.parent === parent.node && source.index < index ? index - 1 : index;
  parent.node.children.splice(Math.max(0, Math.min(offset, parent.node.children.length)), 0, source.node);
  return finish(next, components);
}
export function removeComponent(document: DesignDocument, componentId: string, components: readonly ComponentDescriptor[]) {
  const next = structuredClone(document), target = locate(next, componentId);
  if (!target?.parent || platformComponentBehaviors.get(target.node)?.structural) throw new DesignError('parent');
  const removed = new Set<string>(); walk(target.node, item => removed.add(item.componentId));
  target.parent.children.splice(target.index, 1);
  next.rules = next.rules.filter(rule => !removed.has(rule.targetComponentId));
  // Removing a layout control never silently drops physical data or another scene's field.
  return finish(next, components);
}
export function removeScene(document: DesignDocument, sceneId: string, components: readonly ComponentDescriptor[]) {
  const target = document.scenes.find(scene => scene.sceneId === sceneId);
  if (!target || target.type === 'MAIN') throw new DesignError('parent');
  for (const scene of document.scenes) if (scene.sceneId !== sceneId) walk(scene.root, item => {
    if (item.targetSceneId === sceneId) throw new DesignError('referenced');
  });
  const removed = new Set<string>(); walk(target.root, item => removed.add(item.componentId));
  return finish({...document, scenes: document.scenes.filter(scene => scene.sceneId !== sceneId),
    rules: document.rules.filter(rule => !removed.has(rule.targetComponentId))}, components);
}
export function copyComponent(document: DesignDocument, componentId: string): Clipboard {
  const target = locate(document, componentId);
  if (!target?.parent || platformComponentBehaviors.get(target.node)?.structural) throw new DesignError('parent');
  const nodes = new Set<string>(), fields = new Set<string>();
  walk(target.node, item => {nodes.add(item.componentId); if (item.fieldId) fields.add(item.fieldId);});
  return structuredClone({node: target.node, details: document.details, fields: document.fields.filter(field => fields.has(field.fieldId)),
    rules: document.rules.filter(rule => nodes.has(rule.targetComponentId))});
}
export function pasteComponent(document: DesignDocument, parentId: string, clipboard: Clipboard,
  components: readonly ComponentDescriptor[], id: Identity = identity) {
  const next = structuredClone(document), copied = structuredClone(clipboard), nodes = new Map<string, string>(), fields = new Map<string, string>();
  for (const field of copied.fields) {
    const fieldId = id(); fields.set(field.fieldId, fieldId);
    const storageCode = field.storageCode ? code('field', next.fields.map(field => field.storageCode)) : field.storageCode;
    next.fields.unshift({...field, fieldId, storageCode});
  }
  walk(copied.node, item => {
    const newId = id(); nodes.set(item.componentId, newId); item.componentId = newId;
    if (item.fieldId) item.fieldId = fields.get(item.fieldId)!;
    platformComponentBehaviors.get(item)?.clone?.(item, id, next, {...document, details: copied.details});
  });
  const conditionFields = (condition: DesignDocument['rules'][number]['condition']) => {
    if (condition.fieldId) condition.fieldId = fields.get(condition.fieldId) ?? condition.fieldId;
    condition.children.forEach(conditionFields);
  };
  for (const rule of copied.rules) {
    rule.ruleId = id(); rule.targetComponentId = nodes.get(rule.targetComponentId)!;
    conditionFields(rule.condition); next.rules.push(rule);
  }
  requireParent(next, parentId, copied.node, components).node.children.push(copied.node);
  return {document: finish(next, components), componentId: copied.node.componentId};
}
export function changeNode(document: DesignDocument, componentId: string, update: (node: Node, field?: DesignField, scene?: Scene) => void) {
  const next = structuredClone(document), selected = locate(next, componentId);
  if (!selected) throw new DesignError('parent');
  update(selected.node, next.fields.find(field => field.fieldId === selected.node.fieldId), selected.parent ? undefined : selected.scene);
  return next;
}
export function recordHistory(history: DesignHistory, next: DesignDocument): DesignHistory {
  if (sameDesignDocument(history.present, next)) return history;
  return {past: [...history.past, history.present].slice(-50), present: next, future: []};
}
export function undo(history: DesignHistory): DesignHistory {
  return history.past.length ? {past: history.past.slice(0, -1), present: history.past[history.past.length - 1],
    future: [history.present, ...history.future].slice(0, 50)} : history;
}
export function redo(history: DesignHistory): DesignHistory {
  return history.future.length ? {past: [...history.past, history.present].slice(-50), present: history.future[0],
    future: history.future.slice(1)} : history;
}
