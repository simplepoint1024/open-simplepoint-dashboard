const kinds = ['ALL', 'SELF', 'DEPT', 'DEPT_AND_BELOW', 'CUSTOM', 'EXPRESSION'];
const dependents = ['customDeptIds', 'resourceCode', 'expression'];
export const policyEditable = (property: any) => !!property && property.readOnly !== true
  && (property['x-field-access'] == null || property['x-field-access'] === 'EDITABLE');
export function scopeTableSchema(schema: any, label: (value: string) => string) {
  const next = structuredClone(schema);
  for (const [name, field] of Object.entries(next.properties ?? {})) {
    const property = field as any;
    property['x-ui'] = {...property['x-ui'], 'x-list-visible': ['customDeptIds', 'resourceCode'].includes(name) ? false : property['x-ui']?.['x-list-visible'] ?? true};
  }
  if (next.properties?.type && next.properties.type['x-field-access'] !== 'MASKED') {
    const values = next.properties.type.enum ?? next.properties.type.oneOf?.map((item: any) => item.const) ?? kinds;
    delete next.properties.type.enum;
    next.properties.type.oneOf = values.filter((value: string) => kinds.includes(value)).map((value: string) => ({const: value, title: label(value)}));
  }
  return next;
}
/** Extend the actual engine schema, never reconstitute omitted ACL fields or invent a discriminator. */
export function scopeFormSchema(schema: any, editing: any, pages: Array<{const: string; title: string}>) {
  const next = structuredClone(schema), props = next.properties ?? {};
  for (const [name, property] of Object.entries(props) as [string, any][]) {
    if (property['x-field-access'] === 'MASKED') {
      props[name] = {title: property.title, description: property.description, type: 'string', readOnly: true,
        'x-field-access': 'MASKED', const: String(editing?.[name] ?? '***')};
    }
  }
  const known = kinds.includes(editing?.type);
  if (known && props.type && dependents.some(name => props[name] && !policyEditable(props[name]))) props.type.readOnly = true;
  const mutableType = policyEditable(props.type);
  next.required = [...new Set([...(next.required ?? []), 'name', 'type'])].filter(name => policyEditable(props[name]));
  if (!props.type || !mutableType && !known) {
    // With an unreadable discriminator, preserve readable existing dependencies but do not edit/clear them.
    for (const name of dependents) if (props[name]) props[name].readOnly = true;
    return next;
  }
  const organization = props.customDeptIds && {...props.customDeptIds, ...(policyEditable(props.customDeptIds) ? {uniqueItems: true, minItems: 1, maxItems: 2000} : {})};
  const resource = props.resourceCode && {...props.resourceCode,
    ...(policyEditable(props.resourceCode) && pages.length ? {oneOf: pages} : {readOnly: true})};
  if (resource && props.expression && !policyEditable(props.expression)) resource.readOnly = true;
  // Keep an existing literal target when its catalog is no longer readable/eligible. Do not select a substitute.
  if (resource?.oneOf && editing?.resourceCode && !pages.some(page => page.const === editing.resourceCode)) {
    delete resource.oneOf; resource.readOnly = true;
  }
  const expression = props.expression && {...props.expression, ...(policyEditable(props.expression) ? {minLength: 1, maxLength: 8192} : {})};
  dependents.forEach(name => delete props[name]);
  const branch = (type: string[], properties: any) => ({properties: {type: {enum: type}, ...properties},
    required: Object.keys(properties).filter(name => policyEditable(properties[name]))});
  next.dependencies = {...next.dependencies, type: {oneOf: [
    branch(['CUSTOM'], organization ? {customDeptIds: organization} : {}),
    branch(['EXPRESSION'], {...(resource ? {resourceCode: resource} : {}), ...(expression ? {expression} : {})}),
    branch(kinds.filter(value => !['CUSTOM', 'EXPRESSION'].includes(value)), {}),
  ]}};
  return next;
}
export function prepareScopeData(data: any) {
  if (!kinds.includes(data?.type)) return data;
  return {...data, customDeptIds: data.type === 'CUSTOM' ? data.customDeptIds : [],
    ...(data.type === 'EXPRESSION' ? {} : {resourceCode: null, expression: null})};
}
export function scopeFormUiSchema(draft: any, schema: any) {
  const properties = {...schema?.properties};
  const branch = schema?.dependencies?.type?.oneOf?.find((item: any) => item.properties?.type?.enum?.includes(draft?.type));
  Object.assign(properties, branch?.properties);
  return {'ui:order': ['name', 'type', ...(draft?.type === 'CUSTOM' ? ['customDeptIds'] : draft?.type === 'EXPRESSION' ? ['resourceCode', 'expression'] : []), '*'],
    ...(properties.customDeptIds ? {customDeptIds: {'ui:widget': properties.customDeptIds.type === 'array' ? 'OrgTreeMultiSelect' : 'text'}} : {}),
    ...(properties.expression ? {expression: {'ui:widget': draft?.type === 'EXPRESSION' && policyEditable(properties.expression) ? 'PermissionExpression' : 'textarea'}} : {}),
    description: {'ui:widget': 'textarea'}};
}
