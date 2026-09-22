import {Form} from "@rjsf/antd";
import {FormProps} from "@rjsf/core";
import {Button, Spin} from "antd";
import "./index.css";
import validator from "@rjsf/validator-ajv8";
import {RJSFValidationError, SubmitButtonProps} from "@rjsf/utils";
import {lazy, memo, Suspense, useMemo, type ComponentType} from "react";
import type {WidgetProps} from "@rjsf/utils";
import {useI18n} from "@simplepoint/shared/hooks/useI18n";

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
const OssFileWidget = lazyWidget(() => import('./widgets/OssFileWidget'));
const OssImageWidget = lazyWidget(() => import('./widgets/OssImageWidget'));
const PermissionExpression = lazyWidget(() => import('./widgets/PermissionExpression'));
const UserPicker = lazyWidget(() => import('./widgets/UserPicker'));

type SFormProps = Omit<FormProps, "validator">& {
  i18nNamespaces?: string[];
  hideSubmit?: boolean;
  submitLoading?: boolean;
};

// 自定义提交按钮
const CustomSubmitButton = (props: SubmitButtonProps) => {
  const {t} = useI18n();
  const {uiSchema} = props;
  const {"ui:submitButtonOptions": {submitText} = {}} = (uiSchema || {}) as any;
  const submitLoading = Boolean((props as any)?.registry?.formContext?.submitLoading);
  const text = submitText || t('form.submit', '提交');
  return (
    <Button type="primary" htmlType="submit" style={{backgroundColor: '#00b96b'}} loading={submitLoading} disabled={submitLoading}>
      {text}
    </Button>
  );
};

const formTemplates = {
  ButtonTemplates: {
    SubmitButton: CustomSubmitButton
  },
};

const defaultWidgets = {
  IconPicker,
  PermissionExpression,
  OrgTreeMultiSelect,
  OrganizationSelect,
  RemoteSelect,
  UserPicker,
  OssFile: OssFileWidget,
  OssImage: OssImageWidget,
} as const;

// 抽离 textarea 的 autosize 常量，避免重复创建对象
const TEXTAREA_AUTOSIZE = { minRows: 4, maxRows: 16 } as const;

const SForm = (props: SFormProps) => {
  const {t} = useI18n();
  const {schema, uiSchema, validate, hideSubmit, submitLoading, ...rest} = props as any;

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

  const mergedValidate = (formData: any, errors: any) => {
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
    if (typeof validate === 'function') validate(formData, errors);
    return errors;
  };

  const transformErrors = (errors: RJSFValidationError[]) => errors;
  const resolvedTemplates = useMemo(() => hideSubmit
    ? {ButtonTemplates: {SubmitButton: () => null}}
    : formTemplates, [hideSubmit]);
  return (
    <Form
      {...rest}
      schema={schema}
      uiSchema={mergedUiSchema}
      formContext={{...(rest.formContext || {}), formData: rest.formData, submitLoading}}
      validator={validator}
      validate={mergedValidate}
      showErrorList={false}
      transformErrors={transformErrors}
      templates={resolvedTemplates}
      widgets={defaultWidgets as any}
    />
  );
};

export default memo(SForm);
