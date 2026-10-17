/** Check declarations at this object level, including conditional schemas; never inspect child objects. */
function declaresProperty(root: any, field: string): boolean {
  const seen = new Set<object>();
  const visit = (schema: any): boolean => {
    if (!schema || typeof schema !== 'object' || Array.isArray(schema) || seen.has(schema)) return false;
    seen.add(schema);
    if (Object.prototype.hasOwnProperty.call(schema.properties ?? {}, field)) return true;
    if (typeof schema.$ref === 'string' && schema.$ref.startsWith('#/')) {
      const target = schema.$ref.slice(2).split('/').reduce((node: any, part: string) =>
        node?.[part.replace(/~1/g, '/').replace(/~0/g, '~')], root);
      if (visit(target)) return true;
    }
    return ['allOf', 'anyOf', 'oneOf'].some(key => Array.isArray(schema[key]) && schema[key].some(visit))
      || ['then', 'else'].some(key => visit(schema[key]))
      || ['dependencies', 'dependentSchemas'].some(key => Object.values(schema[key] ?? {}).some(visit));
  };
  return visit(root);
}

/** A contextual form may remove fields supplied by beforeSubmit; do not leave invisible required inputs. */
export function projectedFormSchema(original: any, transformed: any) {
  if (!original?.properties || !transformed?.properties || !Array.isArray(transformed.required)) return transformed;
  const required = transformed.required.filter((field: string) =>
    !Object.prototype.hasOwnProperty.call(original.properties, field) || declaresProperty(transformed, field));
  return required.length === transformed.required.length ? transformed : {...transformed, required};
}


/** Remove only fields explicitly omitted by a contextual schema; keep unknown fields for validation. */
export function projectedFormData(original: any, transformed: any, data: any) {
  if (!original?.properties || !transformed?.properties || !data || typeof data !== 'object') return data;
  const omitted = Object.keys(original.properties).filter(key =>
    !declaresProperty(transformed, key));
  if (!omitted.some(key => Object.prototype.hasOwnProperty.call(data, key))) return data;
  return Object.fromEntries(Object.entries(data).filter(([key]) => !omitted.includes(key)));
}
