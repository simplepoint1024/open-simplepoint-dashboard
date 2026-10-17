type UiSchema = Record<string, any>;
const object = (value: unknown): value is UiSchema => !!value && typeof value === 'object' && !Array.isArray(value);

/** Applies platform widgets at every object/array level, including nested workflow inputs. */
export function schemaUi(schema: unknown): UiSchema {
  if (!object(schema)) return {};
  const result: UiSchema = {};
  const xui = object(schema['x-ui']) ? schema['x-ui'] : {};
  const widget = xui['ui:widget'] ?? xui.widget, options = xui['ui:options'] ?? xui.options;
  if (typeof widget === 'string') {
    result['ui:widget'] = widget;
    if (widget === 'textarea') result['ui:options'] = {autoSize: {minRows: 4, maxRows: 16}, ...options};
    else if (options) result['ui:options'] = options;
  }
  if (object(schema.properties)) for (const [name, field] of Object.entries(schema.properties)) {
    const child = schemaUi(field);
    if (Object.keys(child).length) Object.defineProperty(result, name, {value: child, enumerable: true, configurable: true, writable: true});
  }
  if (object(schema.items)) {
    const items = schemaUi(schema.items);
    if (Object.keys(items).length) result.items = items;
  }
  return result;
}

/** Explicit presentation overrides only the supplied keys; siblings retain their platform widgets. */
export function mergeUiSchema(base: UiSchema, overrides: UiSchema = {}): UiSchema {
  return Object.fromEntries(Object.entries({...base, ...overrides}).map(([key, value]) => [key,
    Object.prototype.hasOwnProperty.call(base, key) && Object.prototype.hasOwnProperty.call(overrides, key) && object(base[key]) && object(overrides[key])
      ? mergeUiSchema(base[key], overrides[key]) : value]));
}
