/** Explicit display-only values are server-owned; they are never editable input to validate. */
export function displayOnlyProjection(source: any, sourceUi: any = {}): {schema: any; uiSchema: any} {
  if (!source || typeof source !== 'object') return {schema: source, uiSchema: sourceUi};
  if (source['x-display-only'] === true) {
    // Attachment references retain their authenticated download interaction in snapshots.
    // This is a trusted widget registration, not an executable renderer from form metadata.
    if (sourceUi?.['ui:widget'] === 'FormAttachments') {
      return {schema: {type: ['string', 'null'], title: source.title, readOnly: true},
        uiSchema: {...sourceUi, 'ui:readonly': true}};
    }
    return {schema: {
      type: ['string', 'number', 'boolean', 'object', 'array', 'null'],
      title: source.title, description: source.description, readOnly: true,
      'x-display-only': true,
      ...(['date', 'date-time', 'time'].includes(source.format) ? {'x-display-format': source.format} : {}),
      'x-display-choices': source.oneOf ?? source.enum?.map((value: unknown) => ({const: value, title: String(value)})),
      ...(Object.prototype.hasOwnProperty.call(source, 'default') ? {default: source.default} : {}),
    }, uiSchema: {...sourceUi, 'ui:field': 'DisplayValue', 'ui:readonly': true}};
  }
  const schema = {...source}, uiSchema = {...sourceUi};
  if (source.properties) {
    schema.properties = Object.fromEntries(Object.entries(source.properties).map(([name, property]) => {
      const child = displayOnlyProjection(property, sourceUi?.[name]);
      // An empty projection must not shadow widgets derived from the field's x-ui contract.
      if (Object.keys(child.uiSchema ?? {}).length) uiSchema[name] = child.uiSchema;
      return [name, child.schema];
    }));
    if (Array.isArray(source.required)) schema.required = source.required.filter((name: string) =>
      source.properties[name]?.['x-display-only'] !== true);
  }
  if (source.items && !Array.isArray(source.items)) {
    const item = displayOnlyProjection(source.items, sourceUi?.items);
    schema.items = item.schema;
    if (Object.keys(item.uiSchema ?? {}).length) uiSchema.items = item.uiSchema;
  }
  return {schema, uiSchema};
}

export function displayValue(value: unknown, choices?: {const?: unknown; title?: string}[]): string {
  if (value == null) return '';
  const choice = choices?.find(item => Object.is(item.const, value));
  if (choice?.title != null) return choice.title;
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}
