import {Form} from "@rjsf/antd";
import {FormProps} from "@rjsf/core";
import {Button} from "antd";
import "./index.css";
import validator from "@rjsf/validator-ajv8";
import {RJSFValidationError, SubmitButtonProps} from "@rjsf/utils";
import {memo, useMemo} from "react";
import IconPicker from "./widgets/IconPicker";
import OrgTreeMultiSelect from "./widgets/OrgTreeMultiSelect";
import RemoteSelect from "./widgets/RemoteSelect";
import {useI18n} from "@simplepoint/shared/hooks/useI18n";

type SFormProps = Omit<FormProps, "validator">& {
  i18nNamespaces?: string[];
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

const defaultWidgets = {IconPicker, OrgTreeMultiSelect, RemoteSelect} as const;

// 抽离 textarea 的 autosize 常量，避免重复创建对象
const TEXTAREA_AUTOSIZE = { minRows: 4, maxRows: 16 } as const;

const SForm = (props: SFormProps) => {
  const {t} = useI18n();
  const {schema, uiSchema, validate, submitLoading, ...rest} = props as any;

  // 从 schema.x-ui.widget 自动生成基础 uiSchema（含通用映射与 textarea 特例）
  const autoUiSchema = useMemo(() => {
    const result: any = {};
    if (!schema || typeof schema !== 'object') return result;
    const propsDef = (schema as any).properties || {};
    for (const k of Object.keys(propsDef)) {
      const xui = propsDef[k]?.['x-ui'];
      if (!xui || typeof xui !== 'object') continue;
      const widget = xui['ui:widget'] ?? xui.widget;
      const uiOptions = xui['ui:options'] ?? xui.options;
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
  return (
    <Form
      {...rest}
      schema={schema}
      uiSchema={mergedUiSchema}
      formContext={{...(rest.formContext || {}), submitLoading}}
      validator={validator}
      validate={mergedValidate}
      showErrorList={false}
      transformErrors={transformErrors}
      templates={formTemplates}
      widgets={defaultWidgets as any}
    />
  );
};

export default memo(SForm);
