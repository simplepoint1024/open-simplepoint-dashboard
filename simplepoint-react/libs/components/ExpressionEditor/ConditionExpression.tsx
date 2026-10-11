import {Alert, Empty, Segmented, Spin} from 'antd';
import {lazy, Suspense, useEffect, useMemo, useRef, useState} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import ConditionEditor from '../Table/ConditionEditor';
import TenantDirectorySelect from '../TenantDirectorySelect';
import type {QueryContract, QueryNode} from '../Table/query';
import {comparisonOperators, conditionExpression, conditionAvailable, conditionLimits, parseConditionExpression, expressionIssue,
  type ExpressionVariable} from './model';

const AdvancedEditor = lazy(() => import('./AdvancedEditor'));

export type ConditionExpressionDraft = {
  value: string; mode: string; draft?: QueryNode; error: string;
};

export default function ConditionExpression({value = '', onChange, variables, disabled, onValidityChange,
  initialDraft, onDraftChange}: {
  value?: string; onChange: (value?: string) => void;
  variables: readonly ExpressionVariable[]; disabled?: boolean;
  onValidityChange?: (valid: boolean) => void;
  initialDraft?: ConditionExpressionDraft;
  onDraftChange?: (draft: ConditionExpressionDraft) => void;
}) {
  const {t} = useI18n();
  const scalarVariables = useMemo(() => variables.filter(variable => variable.type !== 'array'), [variables]);
  const variableIndex = useMemo(() => new Map(scalarVariables.map(variable => [variable.name, variable])), [scalarVariables]);
  const parsed = useMemo(() => {
    if (!value) return {op: 'and', children: []} as QueryNode;
    try {return parseConditionExpression(value);} catch {return undefined;}
  }, [value]);
  const saved = initialDraft?.value === value ? initialDraft : undefined;
  const [mode, setMode] = useState(saved?.mode ?? (parsed && conditionAvailable(parsed, variableIndex) ? 'visual' : 'advanced'));
  const [draft, setDraft] = useState<QueryNode | undefined>(saved?.draft);
  const [error, setError] = useState(saved?.error ?? expressionIssue(value) ?? '');
  const [warning, setWarning] = useState('');
  const validityRef = useRef(onValidityChange);
  validityRef.current = onValidityChange;
  const draftChangeRef = useRef(onDraftChange);
  draftChangeRef.current = onDraftChange;
  const expectedValue = useRef(value);
  useEffect(() => {
    if (expectedValue.current !== value) {
      // Undo, redo and external updates replace the previous transient draft.
      const issue = expressionIssue(value) ?? '';
      setDraft(undefined); setError(issue); validityRef.current?.(!issue);
      expectedValue.current = value;
    }
  }, [value]);
  useEffect(() => {draftChangeRef.current?.({value, mode, draft, error});}, [value, mode, draft, error]);
  useEffect(() => {validityRef.current?.(!error);}, [error]);
  const publishDraft = (nextDraft: QueryNode | undefined, issue: string, nextValue = value, nextMode = mode) => {
    // Input callbacks must publish validity and retained drafts before a same-task save/node switch.
    validityRef.current?.(!issue);
    draftChangeRef.current?.({value: nextValue, mode: nextMode, draft: nextDraft, error: issue});
    setDraft(nextDraft); setError(issue); setMode(nextMode);
  };
  const emit = (next?: string) => {expectedValue.current = next ?? ''; onChange(next);};
  const contract = useMemo<QueryContract>(() => ({version: 1, maxSort: 0, ...conditionLimits,
    fields: Object.fromEntries(scalarVariables.map(variable => [variable.name, {
      operators: variable.type === 'number' ? comparisonOperators
        : comparisonOperators.filter(op => ['equals', 'not:equals', 'is:null', 'is:not:null'].includes(op)),
      sortable: false, javaType: variable.type === 'number' ? 'Double' : variable.type === 'boolean' ? 'Boolean' : 'String',
    }])),
  }), [scalarVariables]);
  const properties = useMemo(() => Object.fromEntries(scalarVariables.map(variable => [variable.name,
    {title: variable.label, type: variable.type,
      format: ['date', 'date-time'].includes(variable.kind ?? '') ? variable.kind : undefined}])), [scalarVariables]);
  const known = (node: QueryNode): boolean => conditionAvailable(node, variableIndex);
  const visual = (draft ?? parsed) && known((draft ?? parsed)!);
  const root = draft ?? parsed;
  // A parsed single comparison still needs a group container to add sibling conditions.
  // This is presentation only: opening or switching modes never rewrites saved XML.
  const visualRoot = root?.children ? root : root ? {op: 'and', children: [root]} : undefined;
  const displayedMode = mode === 'visual' && value && !visual ? 'advanced' : mode;
  return <div style={{display: 'grid', gap: 8}}>
    <Segmented block value={displayedMode} disabled={disabled} options={[
      {value: 'visual', label: t('workflow.editor.visualCondition', '条件配置')},
      {value: 'advanced', label: t('workflow.editor.advanced', '高级表达式')},
    ]} onChange={next => {
      if (disabled) return;
      setWarning('');
      if (next === 'visual' && (!parsed || !known(parsed))) {
        setWarning(t('workflow.editor.keepAdvanced', '当前表达式不能转换为普通配置，请继续使用高级模式')); return;
      }
      if (next === 'advanced' && draft && error) return;
      publishDraft(undefined, expressionIssue(value) ?? '', value, next);
    }}/>
    {warning && <Alert type="warning" showIcon title={warning}/>}
    {error && <Alert type="warning" showIcon title={error}/>}
    {displayedMode === 'advanced' ? <Suspense fallback={<Spin size="small"/>}>
      <AdvancedEditor value={value} variables={variables} disabled={disabled}
        onChange={next => {
          if (disabled) return;
          publishDraft(undefined, expressionIssue(next) ?? '', next, 'advanced'); emit(next || undefined);
        }}/>
    </Suspense> : !scalarVariables.length || !visual
      ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={t('workflow.editor.variablesRequired', '请先配置流程变量，或使用高级表达式')}/>
      : <fieldset disabled={disabled} style={{border: 0, padding: 0, margin: 0,
        pointerEvents: disabled ? 'none' : undefined}}>
        <ConditionEditor node={visualRoot!} contract={contract} properties={properties} multiline
          renderValue={(node, change, input) => {
            const variable = variableIndex.get(node.field ?? '');
            if (!['user', 'role'].includes(variable?.kind ?? '') || node.op.startsWith('is:')) return input;
            return <TenantDirectorySelect kind={variable?.kind === 'role' ? 'roles' : 'users'}
              disabled={disabled} allowClear value={typeof node.value === 'string' ? node.value : undefined}
              onChange={value => change({...node, value})}/>;
          }}
          onChange={next => {
            if (disabled) return;
            if (next.children?.length === 0) {publishDraft(next, '', ''); emit(undefined); return;}
            try {
              const expression = conditionExpression(next, variableIndex);
              publishDraft(next, '', expression); emit(expression);
            }
            catch (reason) {
              // An incomplete visual rule must not erase the last saved condition or permit a save.
              publishDraft(next, (reason as Error).message);
            }
          }}/>
      </fieldset>}
  </div>;
}
