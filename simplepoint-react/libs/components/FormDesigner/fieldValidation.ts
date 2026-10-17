import {businessPattern} from '../../shared/validation/businessPattern.ts';
import type {FormFieldValidation, FormFieldType} from '../FormRuntime/contracts.ts';
import type {TranslateFn} from '@simplepoint/shared/i18n/translator';
import type {RJSFSchema} from '@rjsf/utils';

export const textField = (type: FormFieldType) => type === 'STRING' || type === 'TEXT';
export function validationProperties(t: TranslateFn, capacity = 1048576): RJSFSchema {
  return {type: 'object' as const, title: t('app.designer.validation', '校验规则'), properties: {
    minLength: {type: 'integer', title: t('app.designer.minLength', '最小长度'), minimum: 0, maximum: capacity},
    maxLength: {type: 'integer', title: t('app.designer.maxLength', '最大长度'), minimum: 1, maximum: capacity},
    pattern: {type: 'string', title: t('app.designer.pattern', '正则校验'), maxLength: 512, description: t('app.designer.patternHint', '使用 RE2 正则语法；如需完整匹配，请使用 ^ 和 $。不支持反向引用和环视。')},
    patternMessage: {type: 'string', title: t('app.designer.patternMessage', '正则校验提示'), maxLength: 500},
  }};
}
export function normalizedValidation(value?: FormFieldValidation | null): FormFieldValidation | undefined {
  if (!value) return undefined;
  const result = Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined && item !== null && item !== ''));
  return Object.keys(result).length ? result : undefined;
}
export function validateFieldRules(rule: FormFieldValidation | null | undefined, capacity = 1048576) {
  if (!rule) return;
  for (const key of ['minLength', 'maxLength'] as const) {
    const value = rule[key];
    if (value != null && (!Number.isInteger(value) || value < (key === 'minLength' ? 0 : 1) || value > capacity)) throw Error('Invalid length');
  }
  if (rule.minLength != null && rule.maxLength != null && rule.minLength > rule.maxLength) throw Error('Invalid length range');
  if (rule.patternMessage && (!rule.pattern || !rule.patternMessage.trim() || rule.patternMessage.length > 500)) throw Error('Invalid pattern message');
  if (rule.pattern) {
    if (rule.pattern.length > 512) throw Error('Pattern too long');
    businessPattern(rule.pattern);
  }
}
