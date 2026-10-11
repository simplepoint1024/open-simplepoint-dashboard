import {Form, Widgets, Templates} from "@rjsf/antd";
import {FormProps} from "@rjsf/core";
import type RjsfForm from '@rjsf/core';
import {Button, Spin} from "antd";
import "./index.css";
import validator from "@rjsf/validator-ajv8";
import {RJSFValidationError, SubmitButtonProps} from "@rjsf/utils";
import {forwardRef, lazy, memo, Suspense, useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState, type ComponentType} from "react";
import type {WidgetProps} from "@rjsf/utils";
import type {BaseInputTemplateProps} from '@rjsf/utils';
import {useI18n} from "@simplepoint/shared/hooks/useI18n";
import {dateFormValue} from './dateValue';
import {isNumericSchema, numericInputSchema, numericFormValue} from './numericValue';
import {guardFormSubmit} from './submitCapture';
import {validateDecimalFields} from './decimalValue';
import {beginPreparation} from './preparation';
import {flushSync} from 'react-dom';
import {commitFormInput} from './inputCommit';

// Keep optional widget dependencies out of forms which do not use them.
const lazyWidget = (load: () => Promise<{default: ComponentType<WidgetProps>}>) => {
  const Widget = lazy(load);
  return (props: WidgetProps) => (
    <Suspense fallback={<Spin size="small" />}>
      <Widget {...props} />
    </Suspense>
  );
};

const IconPicker = lazyWidget(() => import('./widgets/IconPicker'));
const OrgTreeMultiSelect = lazyWidget(() => import('./widgets/OrgTreeMultiSelect'));
const OrganizationSelect = lazyWidget(() => import('./widgets/OrganizationSelectWidget'));
const RemoteSelect = lazyWidget(() => import('./widgets/RemoteSelect'));
const EntitySelectWidget = lazyWidget(() => import('./widgets/EntitySelectWidget'));
const OssFileWidget = lazyWidget(() => import('./widgets/OssFileWidget'));
const OssImageWidget = lazyWidget(() => import('./widgets/OssImageWidget'));
const PermissionExpression = lazyWidget(() => import('./widgets/PermissionExpression'));
const UserPicker = lazyWidget(() => import('./widgets/UserPicker'));
const TenantDirectory = lazyWidget(() => import('./widgets/TenantDirectoryWidget'));
const Decimal = lazyWidget(() => import('./widgets/DecimalWidget'));

type SFormProps = Omit<FormProps, "validator">& {
  i18nNamespaces?: string[];
  hideSubmit?: boolean;
  submitLoading?: boolean;
  submitDisabled?: boolean;
  /** Synchronous intent guard before engine validation; return false to refuse this attempt. */
  onBeforeSubmit?: () => boolean | void;
};

/** Allows a surrounding dialog's standard confirmation button to submit the platform form. */
export type SFormHandle = {submit: () => void};

// 自定义提交按钮
const CustomSubmitButton = (props: SubmitButtonProps) => {
  const {t} = useI18n();
  const {uiSchema} = props;
  const {"ui:submitButtonOptions": {submitText} = {}} = (uiSchema || {}) as any;
  const submitLoading = Boolean((props as any)?.registry?.formContext?.submitLoading);
  const submitDisabled = Boolean((props as any)?.registry?.formContext?.submitDisabled);
  const text = submitText || t('form.submit', '提交');
  return (
    <Button type="primary" htmlType="submit" loading={submitLoading} disabled={submitLoading || submitDisabled}>
      {text}
    </Button>
  );
};

const AntdBaseInput = Templates.BaseInputTemplate!;
const ValidatedBaseInput = (props: BaseInputTemplateProps) => <AntdBaseInput {...props}
  schema={numericInputSchema(props.schema)}
  onChange={isNumericSchema(props.schema) ? (value: unknown) => props.onChange(numericFormValue(props.schema, value)) : props.onChange}/>;
const formTemplates = {
  BaseInputTemplate: ValidatedBaseInput,
  ButtonTemplates: {
    SubmitButton: CustomSubmitButton
  },
};
// Expected input validation is rendered inline; do not log submitted user data as console warnings.
const defaultValidationError = (_errors: RJSFValidationError[]) => {};

// Antd-theme v6 sends null when clearing a date even for optional non-nullable strings.
// Keep null for nullable schemas; otherwise use the same absent value as ordinary optional inputs.
const dateWidget = (Widget: ComponentType<WidgetProps>) => (props: WidgetProps) => <Widget {...props}
  value={props.value === '' || props.value == null ? undefined : props.value} onChange={value => props.onChange(dateFormValue(props.schema, value))}/>;

const defaultWidgets = {
  DateWidget: dateWidget(Widgets.DateWidget),
  DateTimeWidget: dateWidget(Widgets.DateTimeWidget),
  IconPicker,
  PermissionExpression,
  OrgTreeMultiSelect,
  OrganizationSelect,
  RemoteSelect,
  EntitySelect: EntitySelectWidget,
  UserPicker,
  TenantDirectory,
  Decimal,
  OssFile: OssFileWidget,
  OssImage: OssImageWidget,
} as const;

// 抽离 textarea 的 autosize 常量，避免重复创建对象
const TEXTAREA_AUTOSIZE = { minRows: 4, maxRows: 16 } as const;

const SForm = forwardRef<SFormHandle, SFormProps>(function SForm(props, ref) {
  const formRef = useRef<RjsfForm>(null);
  useImperativeHandle(ref, () => ({submit: () => formRef.current?.submit()}), []);
  const {t} = useI18n();
  const {schema, uiSchema, validate, customValidate, hideSubmit, submitLoading, submitDisabled, onBeforeSubmit, ...rest} = props as any;
  const preparations = useRef(new Set<symbol>()), mounted = useRef(true);
  const [preparing, setPreparing] = useState(false);
  const available = useRef(false); available.current = !submitLoading && !submitDisabled && !rest.disabled;
  const inputWritable = useRef(false); inputWritable.current = !rest.disabled && !rest.readonly;
  // Opt-in for CodeMirror-like native callbacks; ordinary React widgets keep
  // their normal batching. A clean form's disabled save button must not block edits.
  const commitInput = useCallback((update: () => void) => commitFormInput(update,
    mounted.current && inputWritable.current, flushSync), []);
  useEffect(() => {mounted.current = true; return () => {mounted.current = false; preparations.current.clear();};}, []);
  const prepare = useCallback(() => beginPreparation(preparations.current, mounted.current && available.current,
    busy => {if (mounted.current) setPreparing(busy);}), []);

  // 从 schema.x-ui.widget 自动生成基础 uiSchema（含通用映射与 textarea 特例）
  const autoUiSchema = useMemo(() => {
    const result: any = {};
    if (!schema || typeof schema !== 'object') return result;
    const propsDef = (schema as any).properties || {};
    for (const k of Object.keys(propsDef)) {
      const fieldSchema = propsDef[k];
      const xui = fieldSchema?.['x-ui'];
      const normalizedXui = xui && typeof xui === 'object' ? xui : {};
      const widget = normalizedXui['ui:widget']
        ?? normalizedXui.widget;
      const uiOptions = normalizedXui['ui:options'] ?? normalizedXui.options;
      if (!widget || typeof widget !== 'string') continue;
      if (widget === 'textarea') {
        result[k] = {
          'ui:widget': 'textarea',
          'ui:options': { autoSize: TEXTAREA_AUTOSIZE, ...(uiOptions || {}) },
        };
      } else {
        result[k] = {
          'ui:widget': widget,
          ...(uiOptions ? { 'ui:options': uiOptions } : {}),
        };
      }
    }
    return result;
  }, [schema]);

  // 若未显式设置 icon 的 widget，则默认启用 IconPicker
  const withIconPicker = useMemo(() => {
    const result: any = {...autoUiSchema};
    const iconExists = !!(schema && typeof schema === 'object' && (((schema as any).properties?.icon) || (schema as any).icon));
    if (iconExists) {
      if (!uiSchema?.icon && !result.icon) {
        result.icon = {'ui:widget': 'IconPicker'};
      }
    }
    return result;
  }, [schema, autoUiSchema, uiSchema]);

  // 合并优先级：props.uiSchema >
  const mergedUiSchema = useMemo(() => ({
    ...withIconPicker,
    ...(uiSchema || {})
  }) as any, [withIconPicker, uiSchema]);

  // 需要进行 JSON 校验的字段集合（通过 x-ui.format === 'json' 标记）
  const jsonKeys = useMemo(() => {
    const keys: string[] = [];
    if (schema && typeof schema === 'object') {
      const props = (schema as any).properties || {};
      Object.keys(props).forEach((k) => {
        const fmt = props[k]?.['x-ui']?.format;
        if (fmt === 'json') keys.push(k);
      });
    }
    return keys;
  }, [schema]);

  const mergedValidate = (formData: any, errors: any, validationUiSchema?: any) => {
    validateDecimalFields(schema, formData, errors, (issue, rule) => issue === 'scale'
      ? t('form.decimal.scale', '最多支持 {scale} 位小数', {scale: rule.scale})
      : issue === 'minimum' ? t('form.decimal.minimum', '数值必须{comparison} {minimum}',
        {comparison: rule.exclusiveMinimum ? t('form.decimal.greater', '大于') : t('form.decimal.atLeast', '大于等于'), minimum: rule.minimum})
        : issue === 'maximum' ? t('form.decimal.maximum', '数值必须{comparison} {maximum}',
          {comparison: rule.exclusiveMaximum ? t('form.decimal.less', '小于') : t('form.decimal.atMost', '小于等于'), maximum: rule.maximum})
          : t('form.decimal.invalid', '请填写有效的十进制数值'));
    // 内部 JSON 字段校验
    jsonKeys.forEach((k) => {
      const v = formData?.[k];
      if (v && typeof v === 'string') {
        try {
          JSON.parse(v);
        } catch (_) {
          errors[k]?.addError?.(t('form.jsonInvalid', 'JSON格式不正确'));
        }
      }
    });
    // 外部自定义校验
    const validated = typeof validate === 'function' ? validate(formData, errors, validationUiSchema) ?? errors : errors;
    return typeof customValidate === 'function' ? customValidate(formData, validated, validationUiSchema) ?? validated : validated;
  };

  const transformErrors = (errors: RJSFValidationError[]) => errors;
  const resolvedTemplates = useMemo(() => hideSubmit
    ? {...formTemplates, ButtonTemplates: {SubmitButton: () => null}}
    : formTemplates, [hideSubmit]);
  const resolvedWidgets = useMemo(() => ({...defaultWidgets, ...props.widgets}), [props.widgets]);
  const mergedTemplates = useMemo(() => ({...resolvedTemplates, ...props.templates,
    ButtonTemplates: {...props.templates?.ButtonTemplates, ...resolvedTemplates.ButtonTemplates},
  }), [props.templates, resolvedTemplates]);
  const form = (
    <Form
      {...rest}
      className={['rjsf', rest.className].filter(Boolean).join(' ')}
      ref={formRef}
      schema={schema}
      uiSchema={mergedUiSchema}
      formContext={{...(rest.formContext || {}), formData: rest.formData, submitLoading,
        submitDisabled: submitDisabled || preparing, beginPreparation: prepare, commitInput}}
      validator={validator}
      // Dialog submission and non-native widgets must use the same schema/custom validation.
      noHtml5Validate={rest.noHtml5Validate ?? true}
      onError={rest.onError ?? defaultValidationError}
      customValidate={mergedValidate}
      showErrorList={false}
      transformErrors={transformErrors}
      templates={mergedTemplates}
      widgets={resolvedWidgets as any}
    />
  );
  // RJSF does not forward DOM capture handlers. This transparent event boundary
  // leaves the actual engine/form/widgets intact and does not introduce a provider.
  return <div style={{display: 'contents'}} onSubmitCapture={event => {
    guardFormSubmit(event, formRef.current?.formElement.current, available.current && preparations.current.size === 0, onBeforeSubmit ?? (() => true));
  }}>{form}</div>;
});

export default memo(SForm);
