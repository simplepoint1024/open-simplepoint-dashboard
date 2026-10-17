import type {FormFieldDefinition, FormComponentDefinition, FormValue} from './contracts.ts';
import {choiceValue} from './choiceValues.ts';

export function fieldSchema(field: FormFieldDefinition, component: FormComponentDefinition) {
  const base: Record<string, any> = {
    title: component.label || field.label,
    ...(component.description ? {description: component.description} : {}),
  };
  switch (field.type) {
    case 'BOOLEAN': Object.assign(base, {type: 'boolean'}); break;
    case 'INT32': Object.assign(base, {type: 'integer', minimum: -2147483648, maximum: 2147483647}); break;
    case 'INT64':
      Object.assign(base, {type: ['string', 'null'], pattern: '^[+-]?[0-9]+$', maxLength: 2000,
        'x-decimal': {scale: 0, minimum: '-9223372036854775808', maximum: '9223372036854775807'},
        'x-ui': {widget: 'Decimal'}});
      break;
    case 'DECIMAL':
      Object.assign(base, {type: ['string', 'null'], maxLength: 2000,
        'x-decimal': decimalConstraints(field), 'x-ui': {widget: 'Decimal'}});
      break;
    case 'DATE': Object.assign(base, {type: 'string', format: 'date'}); break;
    case 'DATETIME': Object.assign(base, {type: 'string', format: 'date-time'}); break;
    case 'TIME': Object.assign(base, {type: 'string', format: 'time'}); break;
    case 'UUID': Object.assign(base, {type: 'string', format: 'uuid'}); break;
    case 'JSON': Object.assign(base, {type: 'string', 'x-json-value': true, 'x-ui': {widget: 'textarea', format: 'json'}}); break;
    case 'TEXT': Object.assign(base, {type: 'string'}); break;
    default: Object.assign(base, {type: 'string', ...(field.length ? {maxLength: field.length} : {})});
  }
  if (component.componentType === 'field.rating') Object.assign(base, {minimum: 0, maximum: Number(component.properties.count?.value ?? 5)});
  if (field.choices.length) {
    base.type = field.type === 'BOOLEAN' ? 'boolean' : field.type === 'INT32' ? 'integer' : 'string';
    delete base['x-ui'];
    delete base['x-decimal'];
    delete base.pattern;
    base.oneOf = field.choices.map(choice => ({const: choiceValue(field.type, choice.value), title: choice.label}));
  }
  if (field.validation && (field.type === 'STRING' || field.type === 'TEXT')) {
    const {minLength, maxLength, pattern, patternMessage} = field.validation;
    if (minLength != null) base.minLength = minLength;
    if (maxLength != null) base.maxLength = Math.min(maxLength, field.length ?? maxLength);
    if (pattern) base['x-business-pattern'] = pattern;
    if (patternMessage) base['x-validation-text'] = {pattern: patternMessage};
  }
  const defaultValue = field.choices.length && field.defaultValue?.value != null
    ? choiceValue(field.type, field.defaultValue.value)
    : field.type === 'INT64' && field.defaultValue?.kind === 'INTEGER' ? field.defaultValue.value : defaultFormValue(field.defaultValue);
  if (defaultValue !== undefined) base.default = defaultValue;
  return base;
}

function decimalConstraints(field: FormFieldDefinition) {
  const precision = field.precision ?? 18, scale = field.scale ?? 2;
  const maximum = ('9'.repeat(precision - scale) || '0') + (scale ? '.' + '9'.repeat(scale) : '');
  return {precision, scale, minimum: '-' + maximum, maximum};
}

function formValue(value?: FormValue | null): unknown {
  if (!value || value.kind === 'NULL') return undefined;
  const text = value.value ?? '';
  switch (value.kind) {
    case 'BOOLEAN': return text === 'true';
    case 'INTEGER': return text;
    case 'DECIMAL': return text;
    case 'JSON':
      try { return JSON.parse(text); } catch { return text; }
    default: return text;
  }
}

function defaultFormValue(value?: FormValue | null): unknown {
  if (value?.kind === 'JSON') return value.value ?? '';
  if (value?.kind === 'INTEGER') {
    const numeric = Number(value.value);
    return Number.isSafeInteger(numeric) ? numeric : value.value;
  }
  return formValue(value);
}
