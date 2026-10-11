/** Only actual one-time output may enter the reveal dialog; never copy a masked placeholder. */
export function issuedKeyOf(result: unknown): string | undefined {
  if (!result || typeof result !== 'object') return undefined;
  const value = (result as {issuedKey?: unknown}).issuedKey;
  return typeof value === 'string' && value.trim() !== '' && !/^\*+$/.test(value.trim()) ? value : undefined;
}

export function apiKeyDate(value: unknown): string {
  if (typeof value !== 'string' || !value.trim() || value === '***') return '—';
  const parsed = new Date(value);
  return Number.isFinite(parsed.getTime()) ? parsed.toLocaleString() : '—';
}

/** Only schema-writable policy inputs, never list projections, counters or secrets. */
export function apiKeyPolicyData(schema: {properties?: Record<string, any>} | undefined, values: Record<string, unknown>) {
  return Object.fromEntries(['name', 'enabled', 'description', 'expiresAt', 'rateLimitPerMinute'].flatMap(field => {
    const property = schema?.properties?.[field];
    const access = property?.['x-field-access'];
    return property && !property.readOnly && (access == null || access === 'EDITABLE')
      && Object.prototype.hasOwnProperty.call(values, field) ? [[field, values[field]]] : [];
  }));
}

/** A masked number/date/boolean is unreadable text, not a typed policy value or validation failure. */
export function apiKeyEditorSchema(schema: any, record: Record<string, unknown> | null) {
  const next = structuredClone(schema ?? {});
  const properties = next.properties ?? {};
  const serverFields = ['scopeType', 'tenantId', 'keyPrefix', 'secretHash', 'lastUsedAt', 'usageCount',
    'revokedAt', 'issuedKey', 'createdAt', 'updatedAt', 'createdBy', 'updatedBy', 'deletedAt'];
  for (const field of serverFields) delete properties[field];
  if (Array.isArray(next.required)) next.required = next.required.filter((field: string) => !serverFields.includes(field));
  if (properties.name) properties.name.pattern = '\\S';
  for (const field of ['name', 'enabled', 'description', 'expiresAt', 'rateLimitPerMinute']) {
    const property = properties[field];
    if (property?.['x-field-access'] === 'MASKED' && record?.[field] === '***') {
      properties[field] = {type: 'string', title: property.title, description: property.description,
        const: '***', readOnly: true, 'x-field-access': 'MASKED', 'x-ui': {widget: 'text'}};
    }
  }
  return next;
}
