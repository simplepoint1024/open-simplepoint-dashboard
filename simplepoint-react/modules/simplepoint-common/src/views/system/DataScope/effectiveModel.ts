export type EffectiveScope = {roleId?: string; dataScopeType: string; deptIds: string[]; includeSelf: boolean;
  fieldPermissions: Record<string, string>;
  resourceDataScopes: Record<string, {type: string; departmentIds: string[]; includeSelf: boolean; expressions: string[]}>;
  resourceFieldPermissions: Record<string, Record<string, string>>};
const record = (value: unknown): value is Record<string, any> => !!value && typeof value === 'object' && !Array.isArray(value);
const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(item => typeof item === 'string');
const scopes = new Set(['ALL', 'SELF', 'DEPT', 'DEPT_AND_BELOW', 'CUSTOM', 'EXPRESSION', 'DENY']);
const access = new Set(['EDITABLE', 'VISIBLE', 'MASKED', 'HIDDEN']);
const fields = (value: unknown) => value == null || record(value) && Object.values(value).every(item => access.has(item));
/** Accept real literal booleans/registered policy names; malformed data never becomes an invented ALL/empty policy. */
export function effectiveScope(value: unknown): EffectiveScope | undefined {
  if (!record(value) || !scopes.has(value.dataScopeType) || typeof value.includeSelf !== 'boolean'
    || value.roleId != null && typeof value.roleId !== 'string' || value.deptIds != null && !strings(value.deptIds)
    || !fields(value.fieldPermissions) || value.resourceFieldPermissions != null && (!record(value.resourceFieldPermissions)
      || !Object.values(value.resourceFieldPermissions).every(item => record(item) && fields(item)))
    || value.resourceDataScopes != null && (!record(value.resourceDataScopes) || !Object.values(value.resourceDataScopes).every(rule =>
      record(rule) && scopes.has(rule.type) && typeof rule.includeSelf === 'boolean'
      && (rule.departmentIds == null || strings(rule.departmentIds)) && (rule.expressions == null || strings(rule.expressions))))) return;
  return {roleId: value.roleId ?? undefined, dataScopeType: value.dataScopeType, deptIds: value.deptIds ?? [], includeSelf: value.includeSelf,
    fieldPermissions: value.fieldPermissions ?? {}, resourceFieldPermissions: value.resourceFieldPermissions ?? {},
    resourceDataScopes: Object.fromEntries(Object.entries(value.resourceDataScopes ?? {}).map(([key, rule]: [string, any]) =>
      [key, {type: rule.type, departmentIds: rule.departmentIds ?? [], includeSelf: rule.includeSelf, expressions: rule.expressions ?? []}]))};
}
