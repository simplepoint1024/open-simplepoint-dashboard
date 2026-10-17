import type {QueryContract, QueryNode} from '../Table/query.ts';
import type {FormCondition, FormConditionOperator, FormRuleDefinition, FormFieldType} from '../FormRuntime/contracts.ts';
import {typedFormLiteral} from '../FormRuntime/literals.ts';
import type {DesignDocument} from './contracts.ts';
import {persistentField} from './dataModel.ts';
import {sameDesignValue} from './draftState.ts';

export type RuleEffect = FormRuleDefinition['effect'];
export type RuleEdit = {ruleId: string; targetComponentId: string; effect: RuleEffect; condition: QueryNode};
export class RuleEditError extends Error {
  readonly code: 'conflict' | 'target' | 'condition' | 'literal' | 'limit';
  constructor(code: RuleEditError['code']) {super(code); this.code = code;}
}
export const ruleEffects: readonly RuleEffect[] = ['SHOW', 'HIDE', 'ENABLE', 'DISABLE', 'REQUIRE', 'OPTIONAL'];
const operators: Record<string, FormConditionOperator> = {and: 'AND', or: 'OR', not: 'NOT',
  equals: 'EQUALS', 'not:equals': 'NOT_EQUALS', in: 'IN', 'not:in': 'NOT_IN',
  'is:null': 'IS_EMPTY', 'is:not:null': 'IS_NOT_EMPTY', 'than:greater': 'GREATER_THAN',
  'than:equal:greater': 'GREATER_THAN_OR_EQUAL', 'than:less': 'LESS_THAN', 'than:equal:less': 'LESS_THAN_OR_EQUAL'};
const inverse = Object.fromEntries(Object.entries(operators).map(([key, value]) => [value, key]));
const ordered = new Set<FormFieldType>(['INT32', 'INT64', 'DECIMAL', 'DATE', 'TIME', 'DATETIME']);
const javaTypes: Record<FormFieldType, string> = {STRING: 'String', TEXT: 'String', BOOLEAN: 'Boolean',
  INT32: 'Integer', INT64: 'Long', DECIMAL: 'BigDecimal', DATE: 'LocalDate', TIME: 'LocalTime', DATETIME: 'Instant', UUID: 'String', JSON: 'String'};

/** Bridge the platform condition tree to the form AST; no expression language or SQL is involved. */
export function ruleQueryContract(document: DesignDocument): QueryContract {
  return {version: 1, maxSort: 0, maxDepth: 3, maxNodes: 32, fields: Object.fromEntries(document.fields.map(field => [field.fieldId,
    {javaType: javaTypes[field.type], sortable: false, operators: Object.keys(operators)
      .filter(op => !['and', 'or', 'not'].includes(op) && (!op.startsWith('than:') || ordered.has(field.type)))}]))};
}

export function ruleCondition(condition: FormCondition): QueryNode {
  const op = inverse[condition.operator];
  if (['AND', 'OR', 'NOT'].includes(condition.operator)) return {op, children: condition.children.map(ruleCondition)};
  const values = condition.values.map(value => value.kind === 'BOOLEAN' ? value.value === 'true' : value.value ?? '');
  return {op, field: condition.fieldId ?? undefined,
    ...(!values.length ? {} : {value: ['IN', 'NOT_IN'].includes(condition.operator) ? values : values[0]})};
}

export function compileCondition(node: QueryNode, document: DesignDocument): FormCondition {
  let count = 0;
  const contract = ruleQueryContract(document), fields = new Map(document.fields.map(field => [field.fieldId, field]));
  const visit = (item: QueryNode, depth: number): FormCondition => {
    if (++count > 32 || depth > 4) throw new RuleEditError('limit');
    const operator = operators[item.op];
    if (!operator || item.where) throw new RuleEditError('condition');
    if (['AND', 'OR', 'NOT'].includes(operator)) {
      if (!item.children?.length || item.field || item.value !== undefined
        || operator === 'NOT' && item.children.length !== 1) throw new RuleEditError('condition');
      return {operator, values: [], children: item.children.map(child => visit(child, depth + 1))};
    }
    const field = fields.get(item.field ?? '');
    if (!field || item.children || !contract.fields[field.fieldId].operators.includes(item.op)) throw new RuleEditError('condition');
    if (operator === 'IS_EMPTY' || operator === 'IS_NOT_EMPTY') {
      if (item.value !== undefined) throw new RuleEditError('condition');
      return {operator, fieldId: field.fieldId, values: [], children: []};
    }
    const set = operator === 'IN' || operator === 'NOT_IN';
    if (set !== Array.isArray(item.value)) throw new RuleEditError('literal');
    const values = Array.isArray(item.value) ? item.value : [item.value];
    if (!values.length || values.length > 100) throw new RuleEditError('limit');
    try {
      return {operator, fieldId: field.fieldId, children: [], values: values.map(value => {
        if (value === undefined) throw Error('Missing literal');
        return typedFormLiteral(field.type, String(value));
      })};
    } catch {throw new RuleEditError('literal');}
  };
  return visit(node, 1);
}

export function ruleTargets(document: DesignDocument, effect: RuleEffect) {
  const fields = new Map(document.fields.map(field => [field.fieldId, field]));
  const result: {id: string; label: string}[] = [];
  for (const scene of document.scenes) {
    const visit = (node: typeof scene.root, parent: string) => {
      const field = node.fieldId ? fields.get(node.fieldId) : undefined;
      const name = node.label || field?.label || node.componentType;
      const label = `${parent} / ${name}`;
      if (!['REQUIRE', 'OPTIONAL'].includes(effect) || field && persistentField(field)) result.push({id: node.componentId, label});
      node.children.forEach(child => visit(child, label));
    };
    visit(scene.root, scene.name);
  }
  return result;
}

export function newRule(document: DesignDocument): RuleEdit {
  return {ruleId: crypto.randomUUID(), targetComponentId: '', effect: 'SHOW', condition: {op: 'and',
    children: document.fields.length ? [{field: document.fields[0].fieldId, op: 'is:not:null'}] : []}};
}
export function editRule(rule: FormRuleDefinition): RuleEdit {
  return {...rule, condition: ruleCondition(rule.condition)};
}
function requireCurrent(document: DesignDocument, base: FormRuleDefinition) {
  if (!sameDesignValue(document.rules.find(rule => rule.ruleId === base.ruleId), base)) throw new RuleEditError('conflict');
}

export function applyRuleEdit(document: DesignDocument, base: FormRuleDefinition | undefined, edit: RuleEdit): DesignDocument {
  if (base) requireCurrent(document, base);
  else if (document.rules.some(rule => rule.ruleId === edit.ruleId)) throw new RuleEditError('conflict');
  if (!base && document.rules.length >= 500) throw new RuleEditError('limit');
  if (!ruleEffects.includes(edit.effect) || !ruleTargets(document, edit.effect).some(target => target.id === edit.targetComponentId)) throw new RuleEditError('target');
  const rule: FormRuleDefinition = {ruleId: base?.ruleId ?? edit.ruleId, targetComponentId: edit.targetComponentId,
    effect: edit.effect, condition: compileCondition(edit.condition, document)};
  return {...document, rules: base ? document.rules.map(item => item.ruleId === base.ruleId ? rule : item) : [...document.rules, rule]};
}
export function removeRule(document: DesignDocument, base: FormRuleDefinition): DesignDocument {
  requireCurrent(document, base);
  return {...document, rules: document.rules.filter(rule => rule.ruleId !== base.ruleId)};
}
export function moveRule(document: DesignDocument, base: FormRuleDefinition, delta: -1 | 1): DesignDocument {
  requireCurrent(document, base);
  const rules = [...document.rules], index = rules.findIndex(rule => rule.ruleId === base.ruleId), target = index + delta;
  if (target < 0 || target >= rules.length) return document;
  [rules[index], rules[target]] = [rules[target], rules[index]];
  return {...document, rules};
}
