import {Alert, Segmented, Spin} from 'antd';
import {forwardRef, lazy, memo, Suspense, useEffect, useImperativeHandle, useMemo, useRef, useState} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {WorkflowRuntimeForm, WorkflowFormAdapter, WorkflowFormBinding} from '../WorkflowDesigner/contracts';
import {useWorkflowFormAdapters} from './useWorkflowFormAdapters';
import {parseVariables, runtimeSchema, variablesIssue} from './model';
import {captureWorkflowVariables, type WorkflowVariablesSnapshot} from './capture';

const SForm = lazy(() => import('../SForm'));
const JsonVariablesEditor = lazy(() => import('./JsonVariablesEditor'));
export type WorkflowVariablesEditorHandle = {
  getValues: (expectedForm: WorkflowRuntimeForm) => Record<string, unknown>;
};

/** Controlled variable data survives mode switches; invalid JSON remains local and blocks submission. */
const VariablesEditor = forwardRef<WorkflowVariablesEditorHandle, {
  form: WorkflowRuntimeForm;
  stage: 'start' | 'task';
  taskId?: string;
  value: Record<string, unknown>;
  onChange: (value: Record<string, unknown>) => void;
  onValidityChange: (valid: boolean) => void;
  onDirtyChange?: (dirty: boolean) => void;
  disabled?: boolean;
}>(function VariablesEditor({form, stage, taskId, value, onChange, onValidityChange, onDirtyChange, disabled}, ref) {
  const {t} = useI18n();
  const schema = useMemo(() => runtimeSchema(form.formContract.variables, stage), [form.formContract, stage]);
  const [advanced, setAdvanced] = useState(form.formContract.variables.length === 0
    && Object.keys(value).length > 0 && !form.binding);
  const [text, setText] = useState(() => JSON.stringify(value, null, 2));
  const [error, setError] = useState('');
  const validityRef = useRef(onValidityChange);
  validityRef.current = onValidityChange;
  const adapters = useWorkflowFormAdapters();
  const adapter = adapters.find(adapter => adapter.provider === form.binding?.provider);
  const adapterRef = useRef(adapter); adapterRef.current = adapter;
  const renderer = stage === 'start' ? adapter?.renderStartForm : adapter?.renderTaskForm;
  const [providerValidation, setProviderValidation] = useState<{
    adapter?: WorkflowFormAdapter; binding?: WorkflowFormBinding; valid: boolean;
  }>({valid: false});
  const providerValid = providerValidation.adapter === adapter && providerValidation.binding === form.binding
    && providerValidation.valid;
  const issue = variablesIssue(form.formContract.variables, value, stage);
  const valid = !error && !issue && (!form.binding || Boolean(renderer) && providerValid);
  const snapshotRef = useRef<WorkflowVariablesSnapshot>({form, stage, value, providerAvailable: false, providerValid: false});
  snapshotRef.current = {form, stage, value, error, providerAvailable: Boolean(renderer), providerValid};
  useImperativeHandle(ref, () => ({getValues: expectedForm => captureWorkflowVariables(snapshotRef.current, expectedForm)}), []);
  useEffect(() => {validityRef.current(valid);}, [valid]);
  // A saved integration must not silently fall back to another form and bypass its validation.
  if (form.binding && !renderer) return <Alert type="error" showIcon
    title={t('workflow.form.providerUnavailable', '此流程绑定的表单提供方尚未接入，暂时不能提交')}/>;
  const unknown = Object.keys(value).some(key => !schema.properties[key]);
  const change = (next: Record<string, unknown>) => {
    if (disabled || snapshotRef.current.form !== form) return;
    snapshotRef.current = {...snapshotRef.current, value: next};
    onDirtyChange?.(true); onChange(next);
  };
  return <div style={{display: 'grid', gap: 12}}>
    <Segmented block disabled={disabled || Boolean(form.binding)} value={advanced ? 'advanced' : 'normal'} options={[
      {value: 'normal', label: t('workflow.form.normal', '表单填写')},
      {value: 'advanced', label: t('workflow.form.advanced', '高级 JSON')},
    ]} onChange={next => {
      if (next === 'normal' && (error || unknown)) {
        setError(t('workflow.form.keepAdvanced', '数据包含无法转换的字段或无效 JSON，请继续使用高级模式')); return;
      }
      setText(JSON.stringify(value, null, 2)); setAdvanced(next === 'advanced');
    }}/>
    {error && <Alert type="error" showIcon title={error}/>}
    {!error && issue && <Alert type="warning" showIcon title={issue}/>}
    {form.binding && renderer ? renderer({binding: form.binding, taskId: taskId ?? '', value,
      onChange: next => {if (adapterRef.current === adapter) change(next);}, disabled: Boolean(disabled), onValidityChange: valid => {
        if (snapshotRef.current.form !== form || adapterRef.current !== adapter) return;
        snapshotRef.current = {...snapshotRef.current, providerValid: valid};
        setProviderValidation(current => current.adapter === adapter && current.binding === form.binding && current.valid === valid
          ? current : {adapter, binding: form.binding, valid});
      }})
      : advanced ? <Suspense fallback={<Spin size="small"/>}><JsonVariablesEditor value={text} disabled={disabled}
        onChange={next => {
          if (disabled) return;
          onDirtyChange?.(true);
          setText(next);
          try {
            const parsed = parseVariables(next);
            snapshotRef.current = {...snapshotRef.current, value: parsed, error: undefined};
            onChange(parsed); setError('');
          } catch (reason) {
            const error = (reason as Error).message;
            snapshotRef.current = {...snapshotRef.current, error}; setError(error);
          }
        }}/></Suspense>
        : !Object.keys(schema.properties).length ? <Alert type="info" showIcon
          title={t('workflow.form.noVariables', '当前步骤未配置普通表单字段')}/>
        : <Suspense fallback={<Spin size="small"/>}><SForm hideSubmit schema={schema}
          formData={value} disabled={disabled}
          onChange={event => change(event.formData ?? {})}/></Suspense>}
  </div>;
});

export default memo(VariablesEditor);
