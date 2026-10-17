import type {ExpressionVariable} from '../ExpressionEditor/model';
import {expressionIssue} from '../ExpressionEditor/model.ts';
import {reservedVariable} from './contracts.ts';
import type {ActivityLoop, LoopOwner} from './loopCharacteristics';

export type MultiInstanceIssue = 'multiOwner' | 'multiMode' | 'multiSource' | 'multiCount' | 'multiCollection' | 'multiItem' | 'multiCompletion' | 'multiExpression';
export const MULTI_INSTANCE_TYPE = 'bpmn:MultiInstanceLoopCharacteristics';
export const collectionVariable = (value: string) => {
  const match = /^(?:[#$]\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}|([A-Za-z_][A-Za-z0-9_]*))$/.exec(value.trim());
  return match?.[1] ?? match?.[2];
};

/** Same static budget as BpmnMultiInstancePolicy; dynamic values are bounded by the real engine. */
export function multiInstanceIssues(loop: ActivityLoop | undefined, variables: readonly ExpressionVariable[],
  ancestorNames: readonly string[] = []): MultiInstanceIssue[] {
  if (loop?.$type !== MULTI_INSTANCE_TYPE) return [];
  const collection = loop.collection?.trim() ?? '', count = loop.loopCardinality?.body?.trim() ?? '';
  const issues: MultiInstanceIssue[] = [];
  if (!!collection === !!count) issues.push('multiSource');
  if (count && !/^[#$]\{/.test(count) && (!/^[+]?[0-9]+$/.test(count) || Number(count) < 1 || Number(count) > 50)) issues.push('multiCount');
  const name = collectionVariable(collection);
  if (collection && !name && !/^[#$]\{/.test(collection)) issues.push('multiCollection');
  if (name && variables.length && !variables.some(variable => variable.name === name && ['users', 'roles'].includes(variable.kind ?? ''))) issues.push('multiCollection');
  const locals = [loop.elementVariable, loop.elementIndexVariable].filter((value): value is string => !!value);
  if (!collection && loop.elementVariable || new Set(locals).size !== locals.length || locals.some(value =>
    !/^[A-Za-z_][A-Za-z0-9_]{0,79}$/.test(value) || reservedVariable(value)
    || value === name || variables.some(variable => variable.name === value) || ancestorNames.includes(value))) issues.push('multiItem');
  const completion = loop.completionCondition?.body?.trim() ?? '';
  if (completion && !/^[#$]\{/.test(completion)) issues.push('multiCompletion');
  if (/^[#$]\{\s*(?:null|-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?|"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')\s*\}$/.test(completion)) issues.push('multiCompletion');
  if ([collection, count, completion].some(value => /^[#$]\{/.test(value) && expressionIssue(value.replace(/^#/, '$')))) issues.push('multiExpression');
  return [...new Set(issues)];
}

/** Built-in counters are read-only expression choices, not user-submit-able form fields. */
export function multiInstanceCounters(labels: {total: string; active: string; completed: string; index: string}): ExpressionVariable[] {
  return [{name: 'nrOfInstances', label: labels.total, type: 'number', readOnly: true},
    {name: 'nrOfActiveInstances', label: labels.active, type: 'number', readOnly: true},
    {name: 'nrOfCompletedInstances', label: labels.completed, type: 'number', readOnly: true},
    {name: 'loopCounter', label: labels.index, type: 'number', readOnly: true}];
}

export function iterationLocals(owner: LoopOwner | undefined, variables: readonly ExpressionVariable[], label: string): ExpressionVariable[] {
  const result = new Map<string, ExpressionVariable>();
  const visited = new Set<LoopOwner>();
  for (let current = owner; current && !visited.has(current); current = current.$parent) {
    visited.add(current);
    const loop = current.loopCharacteristics;
    if (loop?.$type !== MULTI_INSTANCE_TYPE) continue;
    const collection = variables.find(variable => variable.name === collectionVariable(loop.collection ?? ''));
    if (loop.elementVariable && !result.has(loop.elementVariable)) result.set(loop.elementVariable,
      {name: loop.elementVariable, label: `${loop.elementVariable} · ${label}`, type: 'string',
        kind: collection?.kind === 'roles' ? 'role' : collection?.kind === 'users' ? 'user' : undefined, readOnly: true});
    if (loop.elementIndexVariable && !result.has(loop.elementIndexVariable)) result.set(loop.elementIndexVariable,
      {name: loop.elementIndexVariable, label: `${loop.elementIndexVariable} · ${label}`, type: 'number', readOnly: true});
  }
  return [...result.values()];
}

/** Read every XML node, not only the currently mounted property panel. */
export function multiInstanceXmlIssues(document: Document, variables: readonly ExpressionVariable[]) {
  const bpmn = 'http://www.omg.org/spec/BPMN/20100524/MODEL', flowable = 'http://flowable.org/bpmn';
  const result: Array<{elementId: string; issue: MultiInstanceIssue}> = [];
  const owners = new Set<Element>();
  for (const node of Array.from(document.getElementsByTagNameNS(bpmn, 'multiInstanceLoopCharacteristics'))) {
    const owner = node.parentElement;
    if (!owner) continue;
    const elementId = owner.getAttribute('id') ?? '';
    if (!['userTask', 'subProcess'].includes(owner.localName) || owners.has(owner)) result.push({elementId, issue: 'multiOwner'});
    owners.add(owner);
    const sequential = node.getAttribute('isSequential');
    if (sequential != null && !['true', 'false'].includes(sequential)) result.push({elementId, issue: 'multiMode'});
    const child = (name: string) => {
      const children = Array.from(node.children).filter(item => item.namespaceURI === bpmn && item.localName === name);
      if (children.length > 1) result.push({elementId, issue: 'multiSource'});
      return children.length ? {body: children[0].textContent ?? ''} : undefined;
    };
    const ancestorNames: string[] = [];
    for (let parent = owner.parentElement; parent; parent = parent.parentElement) {
      for (const loop of Array.from(parent.children).filter(item => item.namespaceURI === bpmn && item.localName === 'multiInstanceLoopCharacteristics')) {
        for (const attribute of ['elementVariable', 'elementIndexVariable']) {
          const name = loop.getAttributeNS(flowable, attribute); if (name) ancestorNames.push(name);
        }
      }
    }
    const loop: ActivityLoop = {$type: MULTI_INSTANCE_TYPE,
      collection: node.getAttributeNS(flowable, 'collection') ?? undefined,
      elementVariable: node.getAttributeNS(flowable, 'elementVariable') ?? undefined,
      elementIndexVariable: node.getAttributeNS(flowable, 'elementIndexVariable') ?? undefined,
      loopCardinality: child('loopCardinality'), completionCondition: child('completionCondition')};
    result.push(...multiInstanceIssues(loop, variables, ancestorNames).map(issue => ({elementId, issue})));
  }
  return result;
}

/** Removing or renaming a loop must not leave a simple assignment pointing to a vanished local. */
export function invalidAssignmentVariableNodes(document: Document, variables: readonly ExpressionVariable[]): string[] {
  if (!variables.length) return []; // Existing empty contracts permit externally supplied variables.
  const bpmn = 'http://www.omg.org/spec/BPMN/20100524/MODEL', flowable = 'http://flowable.org/bpmn';
  const invalid: string[] = [];
  for (const task of Array.from(document.getElementsByTagNameNS(bpmn, 'userTask'))) {
    const names = new Set(['initiator', 'true', 'false', 'null', ...variables.map(variable => variable.name)]);
    for (let owner: Element | null = task; owner; owner = owner.parentElement) {
      for (const loop of Array.from(owner.children).filter(item => item.namespaceURI === bpmn && item.localName === 'multiInstanceLoopCharacteristics')) {
        for (const attribute of ['elementVariable', 'elementIndexVariable']) {
          const name = loop.getAttributeNS(flowable, attribute); if (name) names.add(name);
        }
      }
    }
    if (['assignee', 'candidateUsers', 'candidateGroups'].some(attribute => {
      const value = task.getAttributeNS(flowable, attribute)?.trim() ?? '';
      const name = /^[#$]\{/.test(value) ? collectionVariable(value) : undefined;
      return name && !names.has(name);
    })) invalid.push(task.id);
  }
  return invalid;
}
