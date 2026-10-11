export type IdentityProviderRow = {id?: unknown; registrationId?: unknown; displayName?: unknown; [field: string]: unknown};
export type ProviderTarget = {id: string; label?: string; registrationId?: string};
export type ConnectionTestResult = {success: boolean; message?: string; authorizationUri?: string; tokenUri?: string;
  userInfoUri?: string; jwkSetUri?: string; callbackUri?: string};

const fields = ['registrationId', 'displayName', 'preset', 'protocol', 'issuerUri', 'authorizationUri', 'tokenUri',
  'userInfoUri', 'jwkSetUri', 'clientId', 'clientSecret', 'clientAuthenticationMethod', 'scopes', 'userNameAttribute',
  'subjectClaim', 'emailClaim', 'emailVerifiedClaim', 'matchStrategy', 'requireVerifiedEmail', 'allowPrivateNetwork',
  'sortOrder', 'enabled', 'description'];
const defaults: Record<string, unknown> = {preset: 'CUSTOM', protocol: 'OIDC', clientAuthenticationMethod: 'client_secret_basic',
  scopes: 'openid,profile,email', emailClaim: 'email', emailVerifiedClaim: 'email_verified',
  matchStrategy: 'VERIFIED_EMAIL', requireVerifiedEmail: true, allowPrivateNetwork: false, sortOrder: 100, enabled: true};

function writable(property: any): boolean {
  return !!property && typeof property === 'object' && property.readOnly !== true
    && (property['x-field-access'] == null || property['x-field-access'] === 'EDITABLE');
}
function readableText(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() && !/^\*+$/.test(value.trim()) ? value : undefined;
}

/** Capture a literal database ID and only readable labels; masked keys are never request targets. */
export function providerTarget(row: IdentityProviderRow | null | undefined): ProviderTarget | undefined {
  const id = readableText(row?.id);
  return id ? {id, label: readableText(row?.displayName), registrationId: readableText(row?.registrationId)} : undefined;
}

/** Treat the declared boolean strictly and retain only the documented text output. */
export function connectionTestResult(value: unknown): ConnectionTestResult | undefined {
  if (!value || typeof value !== 'object' || typeof (value as ConnectionTestResult).success !== 'boolean') return undefined;
  const source = value as Record<string, unknown>;
  return {success: source.success as boolean, ...Object.fromEntries(
    ['message', 'authorizationUri', 'tokenUri', 'userInfoUri', 'jwkSetUri', 'callbackUri'].map(field => [field, readableText(source[field])]))};
}

/** Do not merge the list record into a write: computed, hidden and readonly values stay server-owned. */
export function providerInput(schema: any, values: Record<string, unknown>, editing: boolean) {
  return Object.fromEntries(fields.flatMap(field => {
    if (editing && field === 'registrationId' || !writable(schema?.properties?.[field])
      || !Object.prototype.hasOwnProperty.call(values, field)) return [];
    if (editing && field === 'clientSecret' && (typeof values[field] !== 'string' || !(values[field] as string).trim())) return [];
    return [[field, values[field]]];
  }));
}

/** Extend the real SForm schema; masked booleans/enums are readonly text, never fabricated typed values. */
export function providerEditorSchema(schema: any, record: IdentityProviderRow | null, secretDescription: string) {
  const next = structuredClone(schema ?? {}), properties = next.properties ?? (next.properties = {});
  for (const field of Object.keys(properties)) if (!fields.includes(field)) delete properties[field];
  const required = new Set<string>();
  for (const field of ['registrationId', 'displayName', 'clientId']) {
    if (!writable(properties[field]) || record && field === 'registrationId') continue;
    properties[field] = {...properties[field], type: 'string', minLength: Math.max(1, Number(properties[field].minLength) || 0), pattern: '\\S'};
    required.add(field);
  }
  // Username/subject defaults depend on the final protocol/preset and belong to the service.
  for (const [field, value] of Object.entries(defaults)) if (writable(properties[field])) properties[field].default ??= value;
  if (record && properties.registrationId) properties.registrationId.readOnly = true;
  if (properties.clientSecret && record) properties.clientSecret.description = secretDescription;
  for (const [field, property] of Object.entries(properties) as [string, any][]) {
    if (property['x-field-access'] === 'MASKED' && typeof record?.[field] === 'string' && /^\*+$/.test((record[field] as string).trim())) {
      properties[field] = {type: 'string', title: property.title, description: property.description, const: record[field],
        readOnly: true, 'x-field-access': 'MASKED', 'x-ui': {widget: 'text'}};
    }
  }
  next.required = [...required];
  if (!record && writable(properties.clientSecret)) next.allOf = [...(next.allOf ?? []), {
    if: {properties: {clientAuthenticationMethod: {const: 'none'}}, required: ['clientAuthenticationMethod']},
    then: {}, else: {required: ['clientSecret'], properties: {clientSecret: {type: 'string', minLength: 1, pattern: '\\S'}}},
  }];
  return next;
}
