/** Respect explicit field ACLs without discarding trusted contextual values injected by beforeSubmit. */
export function defaultCrudData(schema: any, values: Record<string, unknown>, captured?: Record<string, unknown> | null) {
  const data = Object.fromEntries(Object.entries(values).filter(([field]) => {
    const access = schema?.properties?.[field]?.['x-field-access'];
    return access == null || access === 'EDITABLE';
  }));
  if (captured) data.id = captured.id;
  return data;
}
