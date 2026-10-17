import {matrixSize} from '../FormComponents/Matrix/model.ts';
import type {
  ApplicationFormDocument,
  FormComponentDefinition,
  FormFieldDefinition,
  FormRuleDefinition,
  FormValue,
} from './contracts.ts';
import {conditionMatches} from './conditions.ts';
import {fieldSchema} from './fieldSchema.ts';
import {detailSchema} from '../FormComponents/Detail/schema.ts';
import {fieldNullability} from './fieldNullability.ts';
import {fieldWriteAllowed, fieldOperationHidden} from './writePolicy.ts';
import type {RecordState} from './recordTableModel.ts';
import {platformFormComponents, type FormComponentRegistry, type FormActionKind} from './componentRegistry.ts';

type LayoutBase = {key: string; columnSpan: number; hidden: boolean};
export type RuntimeLayoutItem = LayoutBase & (
  | {kind: 'field'; fieldId: string}
  | {kind: 'display'; title?: string; text?: string; component: FormComponentDefinition}
  | {kind: 'container'; layout: 'grid' | 'group' | 'tabs' | 'matrix' | 'cell'; columns?: number; title?: string; children: RuntimeLayoutItem[]}
  | {kind: 'action'; action: RuntimeFormAction}
);

export type RuntimeFormModel = {
  schema: Record<string, any>;
  uiSchema: Record<string, any>;
  layout: RuntimeLayoutItem[];
  sceneName: string;
  sceneType: 'MAIN' | 'DIALOG' | 'DRAWER';
  sceneWidth?: number;
  actions: RuntimeFormAction[];
  fieldIds: string[];
  editableFieldIds: string[];
};

export type RuntimeFormAction = {
  id: string;
  key: FormActionKind;
  label?: string;
  targetSceneId?: string;
  hidden: boolean;
  disabled: boolean;
};

export type RuntimeFormModelOptions = {enforceRequired?: boolean; registry?: FormComponentRegistry; recordState?: RecordState; readOnly?: boolean};
type ComponentState = {hidden: boolean; disabled: boolean; required: boolean};

/** Compiles one scene while evaluating its rules against the complete shared record. */
export function runtimeFormModel(
  document: ApplicationFormDocument,
  sceneId: string,
  formData: Record<string, unknown> = {},
  options: RuntimeFormModelOptions = {},
): RuntimeFormModel {
  if (document.contractVersion !== '1') throw new Error('Unsupported form document version');
  const scene = document.scenes.find(candidate => candidate.sceneId === sceneId);
  if (!scene) throw new Error(`Form scene is unavailable: ${sceneId}`);
  const registry = options.registry ?? platformFormComponents;
  const fields = new Map(document.fields.map(field => [field.fieldId, field]));
  const components = new Map<string, FormComponentDefinition>();
  const visit = (node: FormComponentDefinition) => {
    registry.resolve(node);
    if (components.has(node.componentId)) throw new Error('Duplicate form component identity');
    components.set(node.componentId, node);
    node.children.forEach(visit);
  };
  visit(scene.root);
  const states = componentStates(components, fields, document.rules, formData);
  inheritState(scene.root, states, false, false);
  const properties: Record<string, any> = {};
  const required: string[] = [], editableFieldIds: string[] = [];
  const actions: RuntimeFormAction[] = [];
  const uiSchema: Record<string, any> = {'ui:order': []};
  const compile = (component: FormComponentDefinition): RuntimeLayoutItem => {
    const renderer = registry.resolve(component);
    const state = states.get(component.componentId)!;
    const base = {key: component.componentId, columnSpan: span(component.columnSpan), hidden: state.hidden};
    if (renderer.kind === 'detail') {
      const detail = document.details?.find(item => item.detailId === component.properties.detailId?.value);
      if (!detail || properties[detail.detailId]) throw new Error('Missing or repeated detail binding');
      properties[detail.detailId] = {...detailSchema(detail, options.recordState, options.enforceRequired !== false), title: component.label || detail.label};
      state.disabled ||= Boolean(options.readOnly);
      uiSchema[detail.detailId] = {'ui:field': 'DetailRows', 'ui:disabled': state.disabled,
        'ui:readonly': state.disabled, 'ui:options': {detail, recordState: options.recordState, readOnly: state.disabled}};
      uiSchema['ui:order'].push(detail.detailId);
      if (!state.disabled) editableFieldIds.push(detail.detailId);
      return {...base, kind: 'field', fieldId: detail.detailId};
    }
    if (renderer.kind === 'field') {
      const field = component.fieldId && fields.get(component.fieldId);
      if (!field || Object.prototype.hasOwnProperty.call(properties, field.fieldId)) throw new Error('Missing or repeated scene field');
      state.hidden ||= !options.readOnly && fieldOperationHidden(field, options.recordState);
      base.hidden = state.hidden;
      state.disabled ||= Boolean(options.readOnly) || !fieldWriteAllowed(field, options.recordState);
      state.disabled ||= Boolean(document.constraints?.some(rule => rule.kind === 'DETAIL_SUM' && rule.fieldId === field.fieldId));
      properties[field.fieldId] = fieldSchema(field, component);
      if (state.disabled) Object.assign(properties[field.fieldId], {readOnly: true, 'x-display-only': true});
      const requiredOnCreate = options.recordState === undefined && !fieldWriteAllowed(field, 'DRAFT')
        && (field.submitRequired || state.required && !state.hidden);
      const fieldRequired = !state.disabled
        && (requiredOnCreate || options.enforceRequired !== false && state.required && !state.hidden);
      properties[field.fieldId] = fieldNullability(properties[field.fieldId], !fieldRequired);
      if (fieldRequired) required.push(field.fieldId);
      uiSchema['ui:order'].push(field.fieldId);
      const widget = (typeof renderer.widget === 'function' ? renderer.widget(field) : renderer.widget) ?? (field.choices.length ? 'select' : undefined);
      uiSchema[field.fieldId] = {...fieldUi(component, state, widget), 'ui:options': {componentProperties: component.properties}};
      if (!state.disabled) editableFieldIds.push(field.fieldId);
      return {...base, kind: 'field', fieldId: field.fieldId};
    }
    if (renderer.kind === 'container') {
      return {...base, kind: 'container', layout: renderer.layout,
        title: component.label ?? undefined, ...(renderer.layout === 'matrix' ? {columns: matrixSize(component).columns} : {}), children: component.children.map(compile)};
    }
    if (renderer.kind === 'action') {
      const action: RuntimeFormAction = {id: component.componentId, key: renderer.action,
        label: component.label ?? undefined, targetSceneId: component.targetSceneId ?? undefined,
        hidden: state.hidden, disabled: state.disabled};
      actions.push(action);
      return {...base, kind: 'action', action};
    }
    return {...base, kind: 'display', component, title: component.label ?? undefined,
      text: component.description ?? valueText(component.properties?.text)};
  };
  const root = compile(scene.root);
  if (root.kind !== 'container') throw new Error('Form scene must have a container root');
  uiSchema['ui:order'].push('*');
  return {
    schema: {type: 'object', additionalProperties: false, properties, ...(required.length ? {required} : {})},
    uiSchema,
    layout: root.layout === 'grid' && !root.title ? root.children : [root],
    actions, fieldIds: Object.keys(properties), editableFieldIds,
    sceneName: scene.name, sceneType: scene.type,
    ...(scene.width == null ? {} : {sceneWidth: scene.width}),
  };
}

function inheritState(node: FormComponentDefinition, states: Map<string, ComponentState>, hidden: boolean, disabled: boolean) {
  const state = states.get(node.componentId)!;
  state.hidden ||= hidden;
  state.disabled ||= disabled || node.readOnly;
  node.children.forEach(child => inheritState(child, states, state.hidden, state.disabled));
}

function componentStates(
  components: Map<string, FormComponentDefinition>,
  fields: Map<string, FormFieldDefinition>,
  rules: FormRuleDefinition[],
  formData: Record<string, unknown>,
) {
  const states = new Map<string, ComponentState>();
  components.forEach(component => states.set(component.componentId, {
    hidden: false,
    disabled: component.readOnly,
    required: component.fieldId ? Boolean(fields.get(component.fieldId)?.submitRequired) : false,
  }));
  for (const rule of rules) {
    const state = states.get(rule.targetComponentId);
    if (!state) continue;
    const matches = conditionMatches(rule.condition, formData, fields);
    switch (rule.effect) {
      case 'SHOW': state.hidden = !matches; break;
      case 'HIDE': state.hidden = matches; break;
      case 'ENABLE': state.disabled = !matches; break;
      case 'DISABLE': state.disabled = matches; break;
      case 'REQUIRE': state.required = matches; break;
      case 'OPTIONAL': state.required = !matches; break;
    }
  }
  return states;
}

function fieldUi(component: FormComponentDefinition, state: ComponentState, widget?: string) {
  const result: Record<string, any> = {};
  if (widget) result['ui:widget'] = widget;
  if (component.placeholder) result['ui:placeholder'] = component.placeholder;
  if (state.hidden) result['ui:widget'] = 'hidden';
  if (state.disabled) result['ui:disabled'] = true;
  return result;
}

const span = (value: number) => Math.min(24, Math.max(1, value || 24));
const valueText = (value?: FormValue) => {
  const resolved = value?.kind === 'NULL' ? null : value?.value;
  return resolved == null ? undefined : String(resolved);
};
