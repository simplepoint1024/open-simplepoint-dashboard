import type {RJSFValidationError} from '@rjsf/utils';
import type {TranslateFn} from '../i18n/translator';

/** Error messages contain instructions, never submitted values or raw regular expressions. */
export function validationMessage(keyword: string, params: Record<string, unknown>, t: TranslateFn): string {
  switch (keyword) {
    case 'required': return t('form.validation.required', '请填写此项');
    case 'minLength': return t('form.validation.minLength', '至少填写 {limit} 个字符', params);
    case 'maxLength': return t('form.validation.maxLength', '最多填写 {limit} 个字符', params);
    case 'minimum': return t('form.validation.minimum', '数值不能小于 {limit}', params);
    case 'maximum': return t('form.validation.maximum', '数值不能大于 {limit}', params);
    case 'exclusiveMinimum': return t('form.validation.exclusiveMinimum', '数值必须大于 {limit}', params);
    case 'exclusiveMaximum': return t('form.validation.exclusiveMaximum', '数值必须小于 {limit}', params);
    case 'multipleOf': return t('form.validation.multipleOf', '数值必须是 {multipleOf} 的整数倍', params);
    case 'minItems': return t('form.validation.minItems', '至少选择或添加 {limit} 项', params);
    case 'maxItems': return t('form.validation.maxItems', '最多选择或添加 {limit} 项', params);
    case 'uniqueItems': return t('form.validation.uniqueItems', '不能包含重复项');
    case 'minProperties': return t('form.validation.minProperties', '至少填写 {limit} 个字段', params);
    case 'maxProperties': return t('form.validation.maxProperties', '最多填写 {limit} 个字段', params);
    case 'pattern': return t('form.validation.pattern', '格式不符合要求，请参考字段说明');
    case 'size': return t('form.validation.size', '长度或数量应在 {min} 到 {max} 之间', params);
    case 'enum': case 'const': return t('form.validation.enum', '请选择有效的选项');
    case 'type': return params.type === 'integer'
      ? t('form.validation.integer', '请填写整数')
      : params.type === 'number' ? t('form.validation.number', '请填写有效的数值')
        : t('form.validation.type', '请填写符合字段类型的内容');
    case 'format':
      switch (params.format) {
        case 'email': case 'idn-email': return t('form.validation.email', '请填写有效的邮箱地址');
        case 'uri': case 'url': return t('form.validation.uri', '请填写有效的完整网址');
        case 'date': return t('form.validation.date', '请填写有效的日期');
        case 'date-time': return t('form.validation.dateTime', '请填写有效的日期和时间');
        case 'time': return t('form.validation.time', '请填写有效的时间');
        default: return t('form.validation.format', '请填写符合格式要求的内容');
      }
    default: return t('form.validation.invalid', '填写内容不符合要求，请检查后重试');
  }
}

/** Follow a JSON Pointer, preserving escaped property names and refusing external references. */
function schemaAt(root: any, pointer?: string): any {
  if (pointer === '#') return root;
  if (!pointer?.startsWith('#/')) return undefined;
  return pointer.slice(2).split('/').reduce((node, part) => node?.[part.replace(/~1/g, '/').replace(/~0/g, '~')], root);
}

function errorDefinition(root: any, error: RJSFValidationError): any {
  const parent = schemaAt(root, error.schemaPath?.slice(0, error.schemaPath.lastIndexOf('/')));
  return error.name === 'required' ? parent?.properties?.[error.params?.missingProperty] : parent;
}

function protectedValuePath(root: any, property?: string): boolean {
  const protectedField = (value: any) => value?.readOnly || value?.['x-field-access'] && value['x-field-access'] !== 'EDITABLE';
  let node = root;
  for (const part of (property ?? '').replace(/^\./, '').split('.').filter(Boolean)) {
    if (protectedField(node)) return true;
    const seen = new Set();
    while (node?.$ref && !seen.has(node.$ref)) {
      seen.add(node.$ref);
      const referred = schemaAt(root, node.$ref);
      if (!referred) break;
      node = {...referred, ...Object.fromEntries(Object.entries(node).filter(([key]) => key !== '$ref'))};
      if (protectedField(node)) return true;
    }
    node = /^\d+$/.test(part) && node?.items ? node.items : node?.properties?.[part];
  }
  return Boolean(protectedField(node));
}

export function localizeValidationErrors(errors: RJSFValidationError[], t: TranslateFn, schema?: any): RJSFValidationError[] {
  return errors.filter(error => {
    // Protected values are display-only; server writes independently restore/strip these fields.
    if (protectedValuePath(schema, error.property)) return false;
    const definition = errorDefinition(schema, error);
    if (definition?.readOnly || definition?.['x-field-access'] && definition['x-field-access'] !== 'EDITABLE') return false;
    const segments = error.schemaPath?.split('/') ?? [];
    return !segments.some((_, index) => {
      const ancestor = schemaAt(schema, segments.slice(0, index + 1).join('/'));
      return ancestor?.readOnly || ancestor?.['x-field-access'] && ancestor['x-field-access'] !== 'EDITABLE';
    });
  }).map(error => {
    let message = validationMessage(error.name ?? 'invalid', error.params ?? {}, t);
    const key = errorDefinition(schema, error)?.['x-validation-messages']?.[error.name ?? 'invalid'];
    if (typeof key === 'string' && /^[a-zA-Z][a-zA-Z0-9._-]{0,199}$/.test(key)) message = t(key, message, error.params);
    const title = error.title?.startsWith('i18n:') ? t(error.title.slice(5)) : error.title;
    return {...error, message, stack: title ? `${title}：${message}` : message};
  });
}
