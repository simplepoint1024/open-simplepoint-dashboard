import {officeWorkflow} from '../hr-office/workflows.mjs';

/** Uses existing test approval roles; the runtime still derives candidates from tenant RBAC. */
export function calculationWorkflow(spec, formId, version, suffix) {
  const original = officeWorkflow({...spec, code: spec.code === 'cal_expense' ? 'office_expense_claim' : 'office_daily_report'}, formId, version);
  const key = spec.code + '_' + suffix;
  original.bpmnXml = original.bpmnXml.replaceAll(original.key, key);
  original.key = key; original.category = '计算引擎验收';
  if (spec.code === 'cal_ledger') original.bpmnXml = original.bpmnXml.replaceAll('部门负责人确认日报', '公式验算确认');
  if (spec.code === 'cal_expense') original.formContract.startBinding.variableMappings = {base_amount: 'amount'};
  if (!['cal_delivery', 'cal_assets', 'cal_daily'].includes(spec.code)) return original;
  const task = (id, name, role) => `<userTask id="${id}" name="${name}" flowable:candidateGroups="ROLE_Office_${role}"/>`;
  const edge = (id, from, to) => `<sequenceFlow id="${id}" sourceRef="${from}" targetRef="${to}"/>`;
  const condition = (id, from, to, expression) => `<sequenceFlow id="${id}" sourceRef="${from}" targetRef="${to}"><conditionExpression xsi:type="tFormalExpression"><![CDATA[${expression}]]></conditionExpression></sequenceFlow>`;
  const parallel = spec.code === 'cal_delivery';
  let body = parallel
    ? '<parallelGateway id="split"/><parallelGateway id="join"/>' + task('delivery', '交付负责人评审', 'manager') + task('quality', '测试质量复核', 'finance')
      + edge('begin', 'start', 'split') + edge('delivery_in', 'split', 'delivery') + edge('quality_in', 'split', 'quality')
      + edge('delivery_out', 'delivery', 'join') + edge('quality_out', 'quality', 'join') + edge('check_score', 'join', 'score_gate')
      + '<exclusiveGateway id="score_gate" default="finish"/>' + task('remediation', '低评分整改复核', 'director')
      + condition('low_score', 'score_gate', 'remediation', '${score < 80}') + edge('finish', 'score_gate', 'end') + edge('remediation_done', 'remediation', 'end')
    : task('department', '部门预算审核', 'manager') + task('purchase', '采购核实', 'administration') + task('asset', '资产登记确认', 'finance')
      + edge('begin', 'start', 'department') + edge('budget_check', 'department', 'budget_gate')
      + '<exclusiveGateway id="budget_gate" default="purchase_in"/>' + task('budget_exception', '超预算加签', 'director')
      + condition('over_budget', 'budget_gate', 'budget_exception', '${remaining < 0}') + edge('purchase_in', 'budget_gate', 'purchase')
      + edge('budget_approved', 'budget_exception', 'purchase') + edge('asset_in', 'purchase', 'asset') + edge('finish', 'asset', 'end');
  let variable = parallel ? 'score' : 'remaining', binding = parallel ? 'weighted_score' : 'remaining_budget';
  if (spec.code === 'cal_daily') {
    variable = 'hours'; binding = 'total_hours';
    body = task('manager', '负责人确认日报', 'manager') + edge('begin', 'start', 'manager') + edge('hours_check', 'manager', 'hours_gate')
      + '<exclusiveGateway id="hours_gate" default="finish"/>' + task('overtime', '超时工作复核', 'director')
      + condition('over_time', 'hours_gate', 'overtime', '${hours > 8}') + edge('finish', 'hours_gate', 'end') + edge('overtime_done', 'overtime', 'end');
  }
  original.formContract.variables = [{name: variable, label: variable, type: 'decimal', scope: 'start', required: true}];
  original.formContract.startBinding.variableMappings = {[binding]: variable};
  original.bpmnXml = `<?xml version="1.0" encoding="UTF-8"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="https://simplepoint.org/workflow"><process id="${key}" name="${spec.name}" isExecutable="true"><startEvent id="start"/>${body}<endEvent id="end"/></process></definitions>`;
  return original;
}
