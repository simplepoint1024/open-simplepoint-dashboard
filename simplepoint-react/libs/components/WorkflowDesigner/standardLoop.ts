import type {TranslateFn} from '@simplepoint/shared/i18n/translator';
import {expressionIssue} from '../ExpressionEditor/model.ts';
import {STANDARD_LOOP_TYPE, type ActivityLoop} from './loopCharacteristics.ts';

export type StandardLoopIssue = 'loopOwner' | 'loopMaximum' | 'loopTest' | 'loopCondition' | 'compilerReserved';
export function standardLoopIssues(loop?: ActivityLoop): StandardLoopIssue[] {
  if (loop?.$type !== STANDARD_LOOP_TYPE) return [];
  const issues: StandardLoopIssue[] = [];
  if (!Number.isInteger(loop.loopMaximum) || loop.loopMaximum! < 1 || loop.loopMaximum! > 50) issues.push('loopMaximum');
  if (loop.testBefore != null && typeof loop.testBefore !== 'boolean') issues.push('loopTest');
  const condition = loop.loopCondition?.body?.trim();
  if (loop.loopCondition && (!condition || expressionIssue(condition.replace(/^#/, '$'))
    || /^[#$]\{\s*(?:null|-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?|"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')\s*\}$/.test(condition))) issues.push('loopCondition');
  return issues;
}

export function standardLoopMessage(issue: StandardLoopIssue, t: TranslateFn): string {
  switch (issue) {
    case 'loopOwner': return t('workflow.modeling.loopOwner', '循环只能配置在人工任务或子流程上，且不能与其他循环方式重复配置');
    case 'loopMaximum': return t('workflow.modeling.loopMaximum', '循环最大次数必须为 1 至 50 的整数');
    case 'loopTest': return t('workflow.modeling.loopTest', '请选择执行前或执行后判断循环条件');
    case 'loopCondition': return t('workflow.modeling.loopCondition', '循环条件必须为有效的布尔表达式，且只能配置一次');
    case 'compilerReserved': return t('workflow.modeling.compilerReserved', '流程包含仅允许服务器生成的执行元数据');
  }
}

export function standardLoopXmlIssues(document: Document) {
  const bpmn = 'http://www.omg.org/spec/BPMN/20100524/MODEL', reserved = 'https://simplepoint.org/workflow/execution/v1';
  const result: Array<{elementId: string; issue: StandardLoopIssue}> = [];
  for (const element of Array.from(document.getElementsByTagName('*'))) {
    if (element.namespaceURI === reserved || Array.from(element.attributes).some(attribute => attribute.namespaceURI === reserved)) {
      result.push({elementId: element.id || element.parentElement?.id || '', issue: 'compilerReserved'});
    }
  }
  for (const loop of Array.from(document.getElementsByTagNameNS(bpmn, 'standardLoopCharacteristics'))) {
    const owner = loop.parentElement;
    if (!owner) continue;
    const elementId = owner.id;
    const count = Array.from(owner.children).filter(node => node.namespaceURI === bpmn
      && ['standardLoopCharacteristics', 'multiInstanceLoopCharacteristics'].includes(node.localName)).length;
    if (owner.namespaceURI !== bpmn || !['userTask', 'subProcess'].includes(owner.localName) || count !== 1) result.push({elementId, issue: 'loopOwner'});
    const testBefore = loop.getAttribute('testBefore');
    if (testBefore != null && !['true', 'false'].includes(testBefore)) result.push({elementId, issue: 'loopTest'});
    const conditions = Array.from(loop.children).filter(child => child.namespaceURI === bpmn && child.localName === 'loopCondition');
    if (conditions.length > 1) result.push({elementId, issue: 'loopCondition'});
    const maximum = loop.getAttribute('loopMaximum') ?? '';
    result.push(...standardLoopIssues({$type: STANDARD_LOOP_TYPE,
      loopMaximum: /^[+]?[0-9]+$/.test(maximum) ? Number(maximum) : undefined,
      loopCondition: conditions.length ? {body: conditions[0].textContent ?? ''} : undefined}).map(issue => ({elementId, issue})));
  }
  return result;
}
