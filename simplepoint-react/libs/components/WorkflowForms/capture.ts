import type {WorkflowRuntimeForm} from '../WorkflowDesigner/contracts';
import {parseVariables, validateVariablesValue, variablesIssue} from './model.ts';

export type WorkflowVariablesSnapshot = {
  form: WorkflowRuntimeForm;
  stage: 'start' | 'task';
  value: Record<string, unknown>;
  error?: string;
  providerAvailable: boolean;
  providerValid: boolean;
};

/** Submission reads the current editor draft synchronously, never the last rendered valid value. */
export function captureWorkflowVariables(snapshot: WorkflowVariablesSnapshot, expectedForm: WorkflowRuntimeForm) {
  if (snapshot.form !== expectedForm) throw Error('流程表单已切换，请重新确认后提交');
  if (snapshot.error) throw Error(snapshot.error);
  if (snapshot.form.binding && (!snapshot.providerAvailable || !snapshot.providerValid)) {
    throw Error(snapshot.providerAvailable ? '请完成绑定表单的配置与校验' : '此流程绑定的表单提供方尚未接入，暂时不能提交');
  }
  let text: string | undefined;
  validateVariablesValue(snapshot.value);
  try {text = JSON.stringify(snapshot.value);} catch {throw Error('流程变量必须是可序列化的 JSON 对象');}
  if (text == null) throw Error('流程变量必须是 JSON 对象');
  // Recheck size, depth and stage ownership for ordinary controls and provider callbacks too.
  const value = parseVariables(text), issue = variablesIssue(snapshot.form.formContract.variables, value, snapshot.stage);
  if (issue) throw Error(issue);
  return value;
}
