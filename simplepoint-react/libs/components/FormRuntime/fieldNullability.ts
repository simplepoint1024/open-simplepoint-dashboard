/** Keep SQL NULL distinct from an absent property, so editing never reapplies a default. */
export function fieldNullability(schema: Record<string, any>, nullable: boolean): Record<string, any> {
  const types = (Array.isArray(schema.type) ? schema.type : [schema.type])
    .filter((type: string) => type !== 'null');
  if (nullable) types.push('null');
  const result: Record<string, any> = {...schema, type: types.length === 1 ? types[0] : types};
  if (Array.isArray(schema.oneOf)) {
    result.oneOf = schema.oneOf.filter((option: any) => option.const !== null);
    if (nullable) result.oneOf.push({const: null, title: ''});
  }
  return result;
}
