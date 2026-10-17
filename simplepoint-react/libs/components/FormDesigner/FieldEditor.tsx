import {textField, validationProperties} from './fieldValidation';
import {designerFormContext} from './presentation';
import type {RefObject} from 'react';
import type {RJSFSchema} from '@rjsf/utils';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import SForm, {type SFormHandle} from '../SForm';
import type {ComponentDescriptor, DesignDocument, DesignField} from './contracts.ts';
import type {FormFieldType, FormFieldWritePolicy} from '../FormRuntime/contracts.ts';
import {allowedFieldTypes, changeFieldType, persistentField, type FieldEdit} from './dataModel.ts';

type Props = {document: DesignDocument; field: DesignField; value: FieldEdit; components: readonly ComponentDescriptor[];
  writePolicies: readonly FormFieldWritePolicy[]; disabled: boolean; formRef: RefObject<SFormHandle | null>;
  onChange: (value: FieldEdit) => void; onSubmit: (value: FieldEdit) => void};

/** Local edit buffer; the parent commits one validated command to designer history. */
export default function FieldEditor({document, field, value, components, writePolicies, disabled, formRef, onChange, onSubmit}: Props) {
  const {t} = useI18n();
  const properties: NonNullable<RJSFSchema['properties']> = {
    storageCode: {type: 'string', title: t('app.designer.storageCode', '字段编码'), readOnly: true},
    label: {type: 'string', title: t('app.designer.fieldLabel', '字段名称'), minLength: 1, maxLength: 120},
    type: {type: 'string', title: t('app.designer.fieldType', '数据类型'),
      description: t('app.designer.typeHint', '可选类型取决于所有引用控件；已有规则引用的字段需先处理规则再改类型。'),
      enum: allowedFieldTypes(document, field, components)},
    submitRequired: {type: 'boolean', title: t('app.designer.required', '提交时必填')},
  };
  const required = ['label', 'type', 'defaultMode'];
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
      description: t('app.designer.storageRequiredHint', '草稿也必须满足此约束，需要设置非空默认值。')};
    if (writePolicies.length) properties.writePolicy = {type: 'string', title: t('app.designer.writePolicy', '字段写入规则'),
      description: t('app.designer.writePolicyHint', '发布后，写入限制同时约束使用旧版本的页面；旧页面原有的更严格限制仍保留。仅新增时填写的必填字段需在首次保存前填写。'),
      oneOf: [
        {const: 'EDITABLE', title: t('app.designer.writeEditable', '始终可编辑')},
        {const: 'CREATE_ONLY', title: t('app.designer.writeCreateOnly', '仅新增时填写')},
        {const: 'UNTIL_SUBMITTED', title: t('app.designer.writeUntilSubmitted', '提交后不可修改')},
      ].filter(item => writePolicies.includes(item.const as FormFieldWritePolicy))};
  }
  properties.defaultMode = {type: 'string', title: t('app.designer.defaultMode', '默认值'), oneOf: [
    {const: 'NONE', title: t('app.designer.defaultNone', '不设置')},
    {const: 'NULL', title: t('app.designer.defaultNull', '空值（NULL）')},
    {const: 'VALUE', title: t('app.designer.defaultValue', '指定值')},
  ]};
  if (value.defaultMode === 'VALUE') {
    properties.defaultText = {type: 'string', maxLength: 16384, title: t('app.designer.defaultText', '默认内容'),
      description: t('app.designer.defaultHint', '按字段类型填写：布尔值 true/false，日期 YYYY-MM-DD，时间 HH:mm:ss，日期时间使用带时区的 ISO 格式。数字保留精度，JSON 保留原文。')};
    required.push('defaultText');
  }
  if (value.type !== 'JSON' || value.choices.length) properties.choices = {type: 'array', maxItems: 200,
    title: t('app.designer.choices', '选项'), items: {type: 'object', required: ['value', 'label'], properties: {
      value: {type: 'string', title: t('app.designer.choiceValue', '选项值'), minLength: 1, maxLength: 256},
      label: {type: 'string', title: t('app.designer.choiceLabel', '显示名称'), minLength: 1, maxLength: 120},
    }}};
  // This is a typed literal editor. Text widgets preserve INT64/DECIMAL and JSON numeric precision.
  return <SForm formContext={designerFormContext} ref={formRef} idPrefix="data_field" hideSubmit disabled={disabled} omitExtraData={false}
    schema={{type: 'object', properties, required}} formData={{...value, storageCode: field.storageCode ?? ''}}
    uiSchema={{defaultText: {'ui:widget': ['JSON', 'TEXT'].includes(value.type) ? 'textarea' : 'text', 'ui:emptyValue': ''}}}
    onChange={({formData}, changedField) => {
      if (disabled || !changedField || !formData) return;
      const next = {...value, ...formData} as FieldEdit;
      onChange(value.type === next.type ? next : changeFieldType({...next, type: value.type}, next.type as FormFieldType));
    }} onSubmit={({formData}) => {if (!disabled && formData) onSubmit({...value, ...formData} as FieldEdit);}}/>;
}
