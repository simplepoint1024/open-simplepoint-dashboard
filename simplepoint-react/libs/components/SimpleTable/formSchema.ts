/** A contextual form may remove fields supplied by beforeSubmit; do not leave invisible required inputs. */
export function projectedFormSchema(original: any, transformed: any) {
  if (!original?.properties || !transformed?.properties || !Array.isArray(transformed.required)) return transformed;
  const required = transformed.required.filter((field: string) =>
    !Object.prototype.hasOwnProperty.call(original.properties, field) || Object.prototype.hasOwnProperty.call(transformed.properties, field));
  return required.length === transformed.required.length ? transformed : {...transformed, required};
}
