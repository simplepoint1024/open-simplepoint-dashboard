import {Alert, Segmented, Select, Spin} from 'antd';
import {lazy, Suspense, useEffect, useMemo, useRef, useState, type ReactNode} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {expressionIssue, type ExpressionVariable} from './model';
import {valueExpressionPresentation, valueExpressionModeAllowed, fixedExpressionValue, type ValueExpressionMode} from './valueModel';

const AdvancedEditor = lazy(() => import('./AdvancedEditor'));
export type NumericLiteralResolver = (expression: string) => Promise<string>;
export type LiteralPreview = {value?: string; issue?: string};
export type LiteralResolver = (value: string) => Promise<LiteralPreview>;

/** A fixed-value control and variable selector with a lossless, explicit advanced expression mode. */
export default function ValueExpression({value = '', onChange, variables, disabled, renderLiteral, onValidityChange,
  literalIdentity = false, resolveNumericLiteral, resolveLiteral, literalPreviewFailure, validateValue}: {
  value?: string;
  onChange: (value?: string) => void;
  variables: readonly ExpressionVariable[];
  disabled?: boolean;
  renderLiteral: (value: string | undefined, onChange: (value?: string) => void) => ReactNode;
  onValidityChange?: (valid: boolean) => void;
  /** Identity text uses the engine's Number.toString, not the source spelling or JS Number. */
  literalIdentity?: boolean;
  resolveNumericLiteral?: NumericLiteralResolver;
  /** Field-specific scalar conversion; neither the resolver nor view switching writes the saved value. */
  resolveLiteral?: LiteralResolver;
  literalPreviewFailure?: string;
  /** Optional domain checks run synchronously at input and submission-notification boundaries. */
  validateValue?: (value: string) => string | undefined;
}) {
  const {t} = useI18n();
  const validityRef = useRef(onValidityChange);
  validityRef.current = onValidityChange;
  const viewDraft = useRef<{source: string; value: string; onChange: typeof onChange} | undefined>(undefined);
  // Acknowledgement, a changed controlled value or a new owner callback ends the transient input view.
  if (viewDraft.current && (viewDraft.current.value === value || viewDraft.current.source !== value
    || viewDraft.current.onChange !== onChange)) viewDraft.current = undefined;
  const names = useMemo(() => new Set(variables.map(item => item.name)), [variables]);
  const presentation = useMemo(() => valueExpressionPresentation(value, names), [value, names]);
  const [requestedMode, setMode] = useState<ValueExpressionMode>(presentation.defaultMode);
  const numericIdentity = literalIdentity && presentation.numericLiteral;
  const fieldLiteralFor = (source: string, view: ReturnType<typeof valueExpressionPresentation>) =>
    !!resolveLiteral && !!source && (!view.expression || view.constant != null || /^\$\{\s*null\s*\}$/.test(source.trim()));
  const fieldLiteral = fieldLiteralFor(value, presentation);
  const needsResolution = fieldLiteral || numericIdentity;
  const resolver = fieldLiteral ? resolveLiteral : resolveNumericLiteral;
  const [resolved, setResolved] = useState<{source: string; resolver: NumericLiteralResolver | LiteralResolver;
    value?: string; issue?: string; failed?: boolean}>();
  const resolution = resolved?.source === value && resolved.resolver === resolver ? resolved : undefined;
  const valueIssue = validateValue?.(value);
  const simple = (presentation.simple || fieldLiteral) && !resolution?.issue && !valueIssue;
  const mode = requestedMode !== 'advanced' && !simple ? 'advanced' : requestedMode;
  useEffect(() => {
    if (mode !== 'literal' || !needsResolution || !resolver) return;
    let current = true;
    const source = value;
    // Resolution only changes this view; it never emits a modeler command or invalidates the original expression.
    void Promise.resolve().then<string | LiteralPreview>(() => resolver(source)).then(result => {
      if (!current) return;
      const preview: LiteralPreview = typeof result === 'string' ? {value: result} : result;
      if (preview.issue) {validityRef.current?.(false); setMode('advanced');}
      setResolved({source, resolver, ...preview});
    }, () => {
      if (current) setResolved({source, resolver, failed: true});
    });
    return () => {current = false;};
  }, [mode, needsResolution, resolver, value]);
  const [editedAdvanced, setEditedAdvanced] = useState<{value: string; issue?: string}>();
  const syntaxIssue = editedAdvanced?.value === value ? editedAdvanced.issue
    : presentation.expression ? expressionIssue(value) : undefined;
  const validationIssue = syntaxIssue || valueIssue || resolution?.issue;
  useEffect(() => {validityRef.current?.(!validationIssue);}, [validationIssue]);
  useEffect(() => {
    if (editedAdvanced && editedAdvanced.value !== value) setEditedAdvanced(undefined);
  }, [value, editedAdvanced]);
  const [error, setError] = useState('');
  const pending = !!value && (mode === 'literal' && presentation.expression
    || mode === 'variable' && !presentation.declaredVariable || mode === 'advanced' && !presentation.expression);
  const emit = (next?: string) => {
    if (disabled) return;
    setEditedAdvanced(undefined);
    const nextValue = next ?? '';
    viewDraft.current = {source: value, value: nextValue, onChange};
    validityRef.current?.(!((/^(?:\$|#)\{/.test(nextValue.trim()) && expressionIssue(nextValue)) || validateValue?.(nextValue)));
    onChange(next);
  };
  return <div style={{display: 'grid', gap: 8}}>
    <Segmented block value={mode} disabled={disabled} options={[
      {value: 'literal', label: t('workflow.editor.fixedValue', '固定值')},
      {value: 'variable', label: t('workflow.editor.variable', '流程变量'), disabled: variables.length === 0},
      {value: 'advanced', label: t('workflow.editor.advanced', '高级表达式')},
    ]} onChange={next => {
      setError('');
      const currentValue = viewDraft.current?.value ?? value;
      const currentView = currentValue === value ? presentation : valueExpressionPresentation(currentValue, names);
      const currentResolution = resolved?.source === currentValue && resolved.resolver === resolver ? resolved : undefined;
      const currentSimple = (currentView.simple || fieldLiteralFor(currentValue, currentView))
        && !currentResolution?.issue && !validateValue?.(currentValue);
      if (!valueExpressionModeAllowed(next as ValueExpressionMode, currentSimple, variables.length)) {
        setError(t('workflow.editor.keepAdvanced', '当前表达式不能转换为普通配置，请继续使用高级模式')); return;
      }
      // A mode is an editing view, not an implicit assignment or modeler command.
      setMode(next as ValueExpressionMode);
    }}/>
    {(validationIssue || error) && <Alert type="warning" showIcon title={validationIssue || error}/>}
    {pending && <Alert type="info" showIcon title={t('workflow.editor.valueModePending', '原配置已保留，选择或编辑新的值后才会替换')}/>}
    {mode === 'advanced' ? <>
      <Suspense fallback={<Spin size="small"/>}><AdvancedEditor value={presentation.advancedValue} variables={variables}
        disabled={disabled} onChange={next => {
          if (disabled) return;
          const issue = expressionIssue(next) || validateValue?.(next);
          viewDraft.current = {source: value, value: next, onChange};
          // An actual advanced edit is syntax, unlike the preview of an existing fixed value.
          setEditedAdvanced({value: next, issue}); setError('');
          validityRef.current?.(!issue); onChange(next || undefined);
        }}/></Suspense>
    </> : mode === 'variable' ? <Select style={{width: '100%'}} disabled={disabled}
      value={presentation.declaredVariable ? presentation.variable : undefined}
      options={variables.map(item => ({value: item.name, label: item.label}))}
      onChange={next => emit('${' + next + '}')}/>
      : needsResolution && (!resolver || resolution?.failed)
        ? <><Alert type="warning" showIcon title={literalPreviewFailure || t('workflow.editor.literalPreviewFailed', '身份预览暂不可用，原表达式已保留；可返回高级模式或稍后重试')}/>
          {renderLiteral(undefined, next => emit(fixedExpressionValue(next)))}</>
        : needsResolution && !resolution ? <Spin size="small"/>
          : renderLiteral(needsResolution ? resolution?.value : presentation.literalValue, next => emit(fixedExpressionValue(next)))}
  </div>;
}
