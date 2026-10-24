import {localizedTextSchema, localizedDraft, changedLocalizedDraft} from '@simplepoint/shared/i18n/localizedSchema';
import {Alert, Tabs} from 'antd';
import {useContext, useState} from 'react';
import {FormulaError, parseFormula, validateFormula} from '@simplepoint/calculation';
import {calculationFields} from './calculationModel';
import {replaceDesignField} from './fieldAnalysis';
import {calculationErrorText, designIssues} from './calculationDiagnostics';
import FieldDependencies from './FieldDependencies';
import {createIcon} from '@simplepoint/shared/types/icon';
import CalculationFieldEditor, {DesignerCalculationContext} from './CalculationFieldEditor';
import {textField, validationProperties} from './fieldValidation';
import {designerFormContext} from './presentation';
import type {RefObject} from 'react';
import type {RJSFSchema} from '@rjsf/utils';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import SForm, {type SFormHandle} from '../SForm';
import type {ComponentDescriptor, DesignDocument, DesignField} from './contracts.ts';
import type {FormFieldType, FormFieldWritePolicy} from '../FormRuntime/contracts.ts';
import {allowedFieldTypes, applyFieldEdit, changeFieldType, persistentField, calculableField, type FieldEdit} from './dataModel.ts';

type Props = {initialTab?: 'general' | 'calculation'; calculationScope?: string; document: DesignDocument; field: DesignField; value: FieldEdit; components: readonly ComponentDescriptor[];
  writePolicies: readonly FormFieldWritePolicy[]; disabled: boolean; formRef: RefObject<SFormHandle | null>;
  onChange: (value: FieldEdit) => void; onSubmit: (value: FieldEdit) => void};

/** Local edit buffer; the parent commits one validated command to designer history. */
export default function FieldEditor({initialTab = 'general', calculationScope, document, field, value, components, writePolicies, disabled, formRef, onChange, onSubmit}: Props) {
  const {t, locale} = useI18n();
  const context = useContext(DesignerCalculationContext);
  const [calculationError, setCalculationError] = useState<string>();
  const [tab, setTab] = useState(initialTab as string);
  const calculated = calculableField(field) && value.calculationMode === 'calculated';
  const scope = calculationScope ?? 'parent';
  let candidateDocument: DesignDocument | undefined;
  try {
    const next = applyFieldEdit(document, field, value, components, writePolicies,
      !document.fields.some(item => item.fieldId === field.fieldId), scope === 'parent' ? 'form' : 'detail');
    candidateDocument = replaceDesignField(context?.document ?? document, scope, next.fields.find(item => item.fieldId === field.fieldId)!);
  } catch { /* Invalid local attributes stay in the edit buffer and cannot be sent for trial. */ }
  const candidateIssues = candidateDocument ? designIssues(candidateDocument, context?.catalog?.functions) : [];
  const trialDocument = candidateIssues.length ? undefined : candidateDocument;
  const properties: NonNullable<RJSFSchema['properties']> = {
    storageCode: {type: 'string', title: t('app.designer.storageCode', '字段编码'), readOnly: true},
    labelLocalization: localizedTextSchema(t('app.designer.fieldLabel', '字段名称')),
    type: {type: 'string', title: t('app.designer.fieldType', '数据类型'),
      description: t('app.designer.typeHint', '可选类型取决于所有引用控件；已有规则引用的字段需先处理规则再改类型。'),
      enum: allowedFieldTypes(document, field, components)},
    submitRequired: {type: 'boolean', title: t('app.designer.required', '提交时必填')},
  };
  const required = ['labelLocalization', 'type', ...(calculated ? [] : ['defaultMode'])];
  if (calculableField(field)) properties.calculationMode = {type: 'string', title: t('app.calculation.mode', '字段来源'), oneOf: [
    {const: 'stored', title: t('app.calculation.stored', '用户填写')},
    {const: 'calculated', title: t('app.calculation.calculated', '公式计算')},
  ]};
  if (textField(value.type)) properties.validation = validationProperties(t, value.length ?? undefined);
  if (value.type === 'STRING') {
    properties.length = {type: 'integer', title: t('app.designer.storageLength', '存储长度'), minimum: 1, maximum: 65535};
    required.push('length');
  }
  if (value.type === 'DECIMAL') {
    properties.precision = {type: 'integer', title: t('app.designer.precision', '总位数'), minimum: 1, maximum: 38};
    properties.scale = {type: 'integer', title: t('app.designer.scale', '小数位数'), minimum: 0, maximum: value.precision ?? 38};
    required.push('precision', 'scale');
  }
  if (persistentField(field)) {
    properties.storageRequired = {type: 'boolean', title: t('app.designer.storageRequired', '存储非空'),
      description: calculated ? t('app.calculation.requiredHint', '每次保存时，计算结果都必须非空。') : t('app.designer.storageRequiredHint', '草稿也必须满足此约束，需要设置非空默认值。')};
    if (!calculated && writePolicies.length) properties.writePolicy = {type: 'string', title: t('app.designer.writePolicy', '字段写入规则'),
      description: t('app.designer.writePolicyHint', '发布后，写入限制同时约束使用旧版本的页面；旧页面原有的更严格限制仍保留。仅新增时填写的必填字段需在首次保存前填写。'),
      oneOf: [
        {const: 'EDITABLE', title: t('app.designer.writeEditable', '始终可编辑')},
        {const: 'CREATE_ONLY', title: t('app.designer.writeCreateOnly', '仅新增时填写')},
        {const: 'UNTIL_SUBMITTED', title: t('app.designer.writeUntilSubmitted', '提交后不可修改')},
      ].filter(item => writePolicies.includes(item.const as FormFieldWritePolicy))};
  }
  if (!calculated) properties.defaultMode = {type: 'string', title: t('app.designer.defaultMode', '默认值'), oneOf: [
    {const: 'NONE', title: t('app.designer.defaultNone', '不设置')},
    {const: 'NULL', title: t('app.designer.defaultNull', '空值（NULL）')},
    {const: 'VALUE', title: t('app.designer.defaultValue', '指定值')},
  ]};
  if (!calculated && value.defaultMode === 'VALUE') {
    properties.defaultText = {type: 'string', maxLength: 16384, title: t('app.designer.defaultText', '默认内容'),
      description: t('app.designer.defaultHint', '按字段类型填写：布尔值 true/false，日期 YYYY-MM-DD，时间 HH:mm:ss，日期时间使用带时区的 ISO 格式。数字保留精度，JSON 保留原文。')};
    required.push('defaultText');
  }
  if (value.type !== 'JSON' || value.choices.length) properties.choices = {type: 'array', maxItems: 200,
    title: t('app.designer.choices', '选项'), items: {type: 'object', required: ['value', 'labelLocalization'], properties: {
      value: {type: 'string', title: t('app.designer.choiceValue', '选项值'), minLength: 1, maxLength: 256},
      labelLocalization: localizedTextSchema(t('app.designer.choiceLabel', '显示名称')),
    }}};
  const fromEditor = (data: any): FieldEdit => {
    const next = {...value, ...data} as FieldEdit;
    next.labelLocalization = changedLocalizedDraft(value.label, value.labelLocalization, next.labelLocalization ?? undefined, locale);
    next.label = next.labelLocalization?.defaultValue ?? value.label;
    next.choices = next.choices.map((choice, index) => {
      const previous = value.choices[index];
      const localization = changedLocalizedDraft(previous?.label ?? '', previous?.labelLocalization, choice.labelLocalization ?? undefined, locale);
      return {value: choice.value, label: localization?.defaultValue ?? previous?.label ?? '', ...(localization ? {labelLocalization: localization} : {})};
    });
    return next;
  };
  const submit = (edit: FieldEdit) => {
    if (disabled) return;
    if (calculated) {
      try {
        if (!context?.catalog) throw new Error('Calculation catalog unavailable');
        validateFormula(parseFormula(edit.expression ?? ''), calculationFields(context.document, calculationScope ?? 'parent', field.fieldId), context.catalog.functions);
        if (!candidateDocument) throw new Error('Invalid calculation metadata');
        const issue = candidateIssues.find(issue => issue.scope === scope && issue.fieldId === field.fieldId);
        if (issue) throw new FormulaError(issue.code);
      } catch (error) {setCalculationError(error instanceof FormulaError ? error.code : 'UNAVAILABLE'); setTab('calculation'); return;}
    }
    setCalculationError(undefined); onSubmit(edit);
  };
  // This is a typed literal editor. Text widgets preserve INT64/DECIMAL and JSON numeric precision.
  const basic = <SForm formContext={designerFormContext} ref={formRef} idPrefix="data_field" hideSubmit disabled={disabled} omitExtraData={false}
    schema={{type: 'object', properties, required}} formData={{...value, labelLocalization: localizedDraft(value.label, value.labelLocalization, locale),
      choices: value.choices.map(choice => ({...choice, labelLocalization: localizedDraft(choice.label, choice.labelLocalization, locale)})), storageCode: field.storageCode ?? ''}}
    uiSchema={{defaultText: {'ui:widget': ['JSON', 'TEXT'].includes(value.type) ? 'textarea' : 'text', 'ui:emptyValue': ''}}}
    onChange={({formData}, changedField) => {
      if (disabled || !changedField || !formData) return;
      const next = fromEditor(formData);
      if (next.calculationMode === 'calculated' && value.calculationMode !== 'calculated') {
        next.defaultMode = 'NONE'; next.defaultText = ''; next.writePolicy = 'EDITABLE';
        next.expression = next.expression || 'NULL';
      }
      onChange(value.type === next.type ? next : changeFieldType({...next, type: value.type}, next.type as FormFieldType));
    }} onError={() => setTab('general')} onSubmit={({formData}) => {if (!disabled && formData) submit(fromEditor(formData));}}/>;
  return <>{calculationError && <Alert type="error" showIcon title={calculationError === 'UNAVAILABLE'
    ? t('app.calculation.applyInvalid', '请检查公式中的字段、函数和参数，并确认函数目录已加载。') : calculationErrorText(calculationError, t)}/>}
    <Tabs activeKey={!calculated && tab === 'calculation' ? 'general' : tab} onChange={setTab} items={[
    {key: 'general', icon: createIcon('SettingOutlined'), label: t('app.designer.general', '基础属性'), forceRender: true, children: basic},
    ...(calculated ? [{key: 'calculation', icon: createIcon('CalculatorOutlined'), label: t('app.calculation.title', '计算公式'), children:
      <CalculationFieldEditor field={field} expression={value.expression ?? ''} scope={calculationScope} disabled={disabled}
        trialDocument={trialDocument}
        onChange={expression => {setCalculationError(undefined); onChange({...value, expression});}}/>},
    ] : []),
    {key: 'dependencies', icon: createIcon('BranchesOutlined'), label: t('app.calculation.dependencies', '依赖关系'), children:
      <FieldDependencies document={candidateDocument ?? context?.document ?? document} target={{scope, fieldId: field.fieldId}}/>},
  ]}/></>;
}
