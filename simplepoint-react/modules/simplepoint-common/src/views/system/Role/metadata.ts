export class RoleMetadataError extends Error {
  constructor() {super('ROLE_METADATA_INVALID_AFTER_NORMALIZATION');}
}

/** Honor actual writable schema fields; normalization must neither change readonly values nor bypass lengths. */
export function normalizeRoleMetadata(data: Record<string, unknown>, properties: Record<string, unknown>) {
  const next = {...data};
  for (const key of ['roleName', 'name', 'authority', 'description']) {
    const raw = properties[key];
    if (!raw || typeof raw !== 'object' || Array.isArray(raw) || typeof next[key] !== 'string') continue;
    const field = raw as Record<string, unknown>, access = field['x-field-access'];
    if (field.readOnly === true || access !== undefined && access !== 'EDITABLE') continue;
    const value = (next[key] as string).trim();
    if (typeof field.minLength === 'number' && value.length < field.minLength) throw new RoleMetadataError();
    next[key] = value;
  }
  return next;
}
