import {requiredValueIssues} from './requiredValues';
import {useCalculationPreview} from './useCalculationPreview';
import {calculationInputs, type CalculationPreviewLoader} from './calculationPreview';
import {formRuntimeFields} from '../FormComponents/runtimeFields';
import {detailFormValues} from '../FormComponents/Detail/payload';
import {Alert} from 'antd';
import {useCallback, useEffect, useMemo, useRef, useState, type MutableRefObject, type Ref} from 'react';
import {flushSync} from 'react-dom';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import SForm from '../SForm/Lazy';
import type {SFormHandle} from '../SForm';
import TableActions from '../Table/Actions';
import type {ApplicationFormDocument} from './contracts.ts';
import type {RecordState} from './recordTableModel.ts';
import type {FormComponentRegistry} from './componentRegistry.ts';
import {runtimeFormModel, type RuntimeFormAction} from './model.ts';
import {mergeSceneValues, sceneValues, type FormValues} from './sceneState.ts';
import SceneLayout from './SceneLayout';
import {aggregateIssues, deriveFormTotals} from './aggregateValidation.ts';

export type SceneFormProps = {
  calculationPreview?: CalculationPreviewLoader;
  document: ApplicationFormDocument;
  sceneId: string;
  value: FormValues;
  disabled?: boolean;
  readOnly?: boolean;
  externalSubmit?: boolean;
  recordState?: RecordState;
  submitting?: boolean;
  formRef?: Ref<SFormHandle>;
  registry?: FormComponentRegistry;
  onChange?: (value: FormValues) => void;
  onSubmit?: (value: FormValues) => void;
  onAction?: (action: 'SAVE_DRAFT' | 'SUBMIT', value: FormValues) => void;
  onOpen?: (action: RuntimeFormAction) => void;
  canOpen?: (action: RuntimeFormAction) => boolean;
  onApply?: (value: FormValues) => void;
  onClose?: () => void;
};

const templates = {ObjectFieldTemplate: SceneLayout};

/** One SForm per scene, using shared values for rules and a bounded field projection for edits. */
export default function SceneForm(props: SceneFormProps) {
  const {document, sceneId, value, disabled = false, readOnly = false, externalSubmit = false, submitting = false, registry, recordState,
    calculationPreview, formRef, onChange, onSubmit, onAction, onApply, onClose, onOpen, canOpen} = props;
  const {t} = useI18n();
  const selected = usePageSelected();
  const localForm = useRef<SFormHandle | null>(null), actionLock = useRef(false);
  const [intent, setIntent] = useState<'save' | 'submit' | 'apply'>();
  const [validationAction, setValidationAction] = useState<'save' | 'submit' | 'apply'>('save');
  const calculation = useCalculationPreview(document, value, calculationPreview, !readOnly && !disabled && !submitting);
  const displayValues = calculation.values;
  const [validationNotice, setValidationNotice] = useState('');
  const [validationAttempt, setValidationAttempt] = useState(0);
  const validationFeedback = useRef<HTMLDivElement>(null);
  const [errorFields, setErrorFields] = useState<readonly string[]>([]);
  useEffect(() => {
    if (selected && validationNotice) {
      validationFeedback.current?.focus({preventScroll: true});
      validationFeedback.current?.scrollIntoView({block: 'nearest'});
    }
  }, [selected, validationNotice, validationAttempt]);
  const compilation = useMemo(() => {
    try { return {model: runtimeFormModel(document, sceneId, displayValues, {
      enforceRequired: !onAction && !onApply || recordState === 'SUBMITTED' || validationAction === 'submit', registry, recordState, readOnly,
    })}; } catch (error) { return {error}; }
  }, [document, sceneId, displayValues, onAction, onApply, validationAction, registry, recordState, readOnly]);
  const bindForm = useCallback((handle: SFormHandle | null) => {
    localForm.current = handle;
    if (typeof formRef === 'function') formRef(handle);
    else if (formRef) (formRef as MutableRefObject<SFormHandle | null>).current = handle;
  }, [formRef]);
  if (!compilation.model) return <Alert type="error" showIcon
    title={t('app.runtime.unsupported', '表单包含当前版本不支持的组件，请联系设计者检查。')}/>;
  const model = compilation.model;
  // A detail collection is one editable field; strip its preview-only cells before merging that field.
  const mergeInput = (next: FormValues) => mergeSceneValues(value, calculationInputs(document, next), model);
  const clearIntent = () => {actionLock.current = false; setIntent(undefined);};
  const ready = () => selected && !disabled && !readOnly && !submitting && !actionLock.current;
  const submitAction = (action: 'save' | 'submit' | 'apply') => {
    if (!ready()) return;
    if (!localForm.current) {setValidationAttempt(value => value + 1); setValidationNotice(t('app.submission.formNotReady', '表单尚未就绪，请等待附件上传或当前操作完成后重试。')); return;}
    actionLock.current = true;
    // RJSF derives schema in componentDidUpdate; complete that commit before dispatch.
    flushSync(() => {setIntent(action); setValidationAction(action);});
    if (!localForm.current.submit()) {clearIntent(); setValidationAttempt(value => value + 1); setValidationNotice(t('app.submission.formNotReady', '表单尚未就绪，请等待附件上传或当前操作完成后重试。'));}
  };
  const invoke = (action: RuntimeFormAction) => {
    if (readOnly && action.key !== 'close' && !action.key.startsWith('open-')) return;
    if (!selected || submitting || actionLock.current || action.hidden
      || action.key !== 'close' && (disabled || action.disabled)) return;
    if (action.key === 'save' || action.key === 'submit' || action.key === 'apply') submitAction(action.key);
    else if (action.key === 'reset') onChange?.(mergeInput({}));
    else if (action.key === 'close') onClose?.();
    else if (canOpen?.(action)) onOpen?.(action);
  };
  const renderActions = (actions: RuntimeFormAction[]) => <TableActions selectedKeys={[]} selectedRows={[]}
    buttons={actions.filter(action => !action.hidden && (!externalSubmit || !['save', 'submit'].includes(action.key))
      && (!readOnly || action.key === 'close' || action.key.startsWith('open-'))).map((action, index) => ({
      key: action.id, sort: index, icon: ({save: 'SaveOutlined', submit: 'SendOutlined', apply: 'CheckOutlined',
        close: 'CloseOutlined', reset: 'ReloadOutlined', 'open-dialog': 'ExpandOutlined', 'open-drawer': 'LayoutOutlined'})[action.key], text: action.label ?? actionLabel(action.key, t),
      type: action.key === 'submit' || action.key === 'apply' ? 'primary' as const : 'default' as const,
      disabled: !selected || submitting || Boolean(intent) || action.key !== 'close' && (disabled || action.disabled)
        || action.key.startsWith('open-') && !canOpen?.(action),
      argumentMinSize: 0, argumentMaxSize: 0,
    }))}
    onButtonEvents={Object.fromEntries(actions.map(action => [action.id, () => invoke(action)]))}/>;
  const localAction = (key: 'save' | 'apply' | 'close'): RuntimeFormAction =>
    ({id: `local-${key}`, key, hidden: false, disabled: false});
  const footer: RuntimeFormAction[] = [];
  if (onApply && !model.actions.some(action => action.key === 'apply')) footer.push(localAction('apply'));
  if (onClose && !model.actions.some(action => action.key === 'close')) footer.push(localAction('close'));
  if (onAction && model.sceneType === 'MAIN' && !model.actions.length) footer.push(localAction('save'));
  return <>
    {validationNotice && <div ref={validationFeedback} tabIndex={-1}><Alert role="alert" showIcon type="error" title={validationNotice}/></div>}
    {onAction && recordState !== 'SUBMITTED' && <Alert type="info" showIcon title={t('app.runtime.draftValidationHint', '保存草稿允许暂缺提交必填项；提交时将校验完整数据和明细。')}/>}

    {calculation.loading && <div role="status" className="sp-form-calculation-status">{t('app.calculation.loading', '正在计算…')}</div>}
    {calculation.failed && <Alert type="warning" showIcon title={t('app.calculation.previewFailed', '暂时无法计算，请检查输入或稍后重试。保存时将重新计算。')}/>}
    <SForm customValidate={(data, errors) => {
      if (!readOnly) for (const issue of requiredValueIssues(document, model.schema, data ?? {})) {
        let target: any = errors;
        for (const part of issue.path) target = target?.[part];
        (target ?? errors).addError(t('app.runtime.requiredValue', '{field}不能为空', {field: issue.label}));
      }
      if (!readOnly && (!onAction && !onApply || recordState === 'SUBMITTED' || validationAction === 'submit')) {
        for (const issue of aggregateIssues(document, mergeInput(data ?? {}))) {
          (errors[issue.fieldId] ?? errors).addError(issue.message);
        }
      }
      return errors;
    }} fields={formRuntimeFields} ref={bindForm} schema={model.schema} uiSchema={model.uiSchema}
      idPrefix={`form_${sceneId}`} formData={sceneValues(detailFormValues(document, displayValues), model.fieldIds)}
      disabled={disabled || readOnly || !selected || submitting} submitLoading={submitting}
      hideSubmit={externalSubmit || Boolean(onAction) || Boolean(onApply) || !onSubmit} templates={templates}
      formContext={{labelCol: {flex: `${document.presentation?.labelWidth ?? 140}px`}, wrapperCol: {flex: '1 1 0'}, descriptionLocation: 'tooltip', runtimeLayout: {layout: model.layout, renderAction: (action: RuntimeFormAction) => renderActions([action]), errorFields}}}
      onChange={event => {
        if (selected && !disabled && !readOnly && !submitting && !actionLock.current) {
          onChange?.(deriveFormTotals(document, mergeInput(event.formData ?? {})));
        }
      }}
      onError={errors => {
        setValidationAttempt(value => value + 1);
        setValidationNotice(t('app.runtime.validationFailed', '请检查标记的字段和明细，修正校验错误后重试。'));
        setErrorFields(errors.map(error => (error.property ?? '').replace(/^\./, '').split(/[.\[]/)[0]));
        clearIntent();
      }}
      onSubmit={event => {
        if (disabled || readOnly || submitting || !selected) {clearIntent(); return;}
        const data = mergeInput(event.formData ?? {});
        if (intent === 'apply') onApply?.(data);
        else if (intent && onAction) onAction(intent === 'submit' ? 'SUBMIT' : 'SAVE_DRAFT', data);
        else onSubmit?.(data);
        setValidationNotice(''); setErrorFields([]); clearIntent();
      }}/>
    {footer.length > 0 && <div className="sp-form-runtime-footer">{renderActions(footer)}</div>}
  </>;
}

function actionLabel(action: RuntimeFormAction['key'], t: ReturnType<typeof useI18n>['t']) {
  switch (action) {
    case 'submit': return t('app.runtime.action.submit', '提交');
    case 'reset': return t('app.runtime.action.reset', '重置');
    case 'open-dialog': return t('app.runtime.action.openDialog', '打开弹窗');
    case 'open-drawer': return t('app.runtime.action.openDrawer', '打开抽屉');
    case 'close': return t('app.runtime.action.cancel', '取消');
    case 'apply': return t('app.runtime.action.apply', '确认');
    default: return t('app.runtime.action.saveDraft', '保存草稿');
  }
}
