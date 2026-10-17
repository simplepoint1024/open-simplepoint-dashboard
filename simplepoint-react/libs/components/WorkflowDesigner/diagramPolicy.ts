import {modelingCapabilities as contract} from './modelingCapabilities.ts';
import type {TranslateFn} from '@simplepoint/shared/i18n/translator';

const NS = 'http://www.omg.org/spec/BPMN/20100524/MODEL';
export type DiagramIssue = 'diagramParticipant' | 'diagramCollaboration' | 'diagramReference' | 'diagramLane' | 'diagramTextFormat' | 'diagramDuplicateId';
export function diagramMessage(issue: DiagramIssue, t: TranslateFn): string {
  const messages: Record<DiagramIssue, string> = {
    diagramParticipant: t('workflow.modeling.diagramParticipant', '泳池必须唯一，并引用当前可执行流程；不支持外部参与者'),
    diagramCollaboration: t('workflow.modeling.diagramCollaboration', '当前流程不支持跨泳池消息流或参与者多实例'),
    diagramReference: t('workflow.modeling.diagramReference', '图形引用的对象不存在或类型不正确，请修复关联'),
    diagramLane: t('workflow.modeling.diagramLane', '泳道只能引用所属流程或子流程内的活动节点，且同级泳道不能重复归属'),
    diagramTextFormat: t('workflow.modeling.diagramTextFormat', '文字注释仅支持纯文本，请将文本格式改为 text/plain'),
    diagramDuplicateId: t('workflow.modeling.diagramDuplicateId', '流程元素标识不能重复，请为重复元素设置唯一标识'),
  };
  return messages[issue];
}

/** Structural checks do not assign users, evaluate expressions or execute data associations. */
export function diagramXmlIssues(document: Document): Array<{elementId: string; issue: DiagramIssue}> {
  const elements = Array.from(document.getElementsByTagNameNS(NS, '*'));
  const byId = new Map(elements.filter(e => e.id).map(e => [e.id, e]));
  const named = (name: string) => elements.filter(e => e.localName === name);
  const issues: Array<{elementId: string; issue: DiagramIssue}> = [];
  const report = (element: Element, issue: DiagramIssue) => issues.push({elementId: element.id || element.parentElement?.id || '', issue});
  const ids = new Set<string>();
  for (const element of elements) {
    if (!element.id) continue;
    if (ids.has(element.id)) report(element, 'diagramDuplicateId');
    ids.add(element.id);
  }
  const parentScope = (element: Element): Element | null => {
    let parent = element.parentElement;
    while (parent && !(parent.namespaceURI === NS && ['process', 'subProcess'].includes(parent.localName))) parent = parent.parentElement;
    return parent;
  };
  const process = named('process')[0];
  const pools = named('participant');
  for (const [index, pool] of pools.entries()) {
    if (index >= contract.maxParticipants || !process || byId.get(pool.getAttribute('processRef') ?? '') !== process
      || pool.parentElement?.namespaceURI !== NS || pool.parentElement.localName !== 'collaboration') report(pool, 'diagramParticipant');
  }
  for (const element of elements) {
    if (contract.unsupportedCollaborationElements.includes(element.localName)) report(element, 'diagramCollaboration');
    if (element.localName === 'textAnnotation' && element.hasAttribute('textFormat')
      && element.getAttribute('textFormat') !== contract.annotationFormat) report(element, 'diagramTextFormat');
    for (const [owner, attribute, target] of [['group', 'categoryValueRef', 'categoryValue'],
      ['dataObjectReference', 'dataObjectRef', 'dataObject'], ['dataStoreReference', 'dataStoreRef', 'dataStore']]) {
      if (element.localName !== owner) continue;
      const value = element.getAttribute(attribute);
      // Unbound data stores and unnamed groups are valid descriptive BPMN shapes.
      if (!value && owner !== 'dataObjectReference') continue;
      const referenced = byId.get(value ?? '');
      if (!referenced || referenced.localName !== target
        || (target === 'dataObject' && parentScope(referenced) !== parentScope(element))) report(element, 'diagramReference');
    }
  }
  const memberships = new Map<Element, Set<string>>();
  for (const lane of named('lane')) {
    const laneSet = lane.parentElement;
    const scope = parentScope(lane);
    if (!laneSet || !scope) {report(lane, 'diagramLane'); continue;}
    const seen = memberships.get(laneSet) ?? new Set<string>();
    memberships.set(laneSet, seen);
    for (const reference of Array.from(lane.children).filter(e => e.namespaceURI === NS && e.localName === 'flowNodeRef')) {
      const id = reference.textContent?.trim() ?? '', target = byId.get(id);
      if (!target || !/^(?:.+Task|task|.+Event|.+Gateway|subProcess|callActivity)$/.test(target.localName)
        || parentScope(target) !== scope || seen.has(id)) report(lane, 'diagramLane');
      seen.add(id);
    }
  }
  return issues;
}
