import type {EntityOption} from '../EntitySelect/query';

export type TenantDirectory = 'current' | 'platform';
export type TenantType = 'PLATFORM' | 'PERSONAL' | 'ORGANIZATION';
export function tenantType(value: unknown): TenantType | undefined {
  return value === 'PLATFORM' || value === 'PERSONAL' || value === 'ORGANIZATION' ? value : undefined;
}

/** Logo fields are image locations, never executable URLs, masked values or inline data. */
export function tenantLogo(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const logo = value.trim();
  if (!logo || logo.length > 1024 || /[\u0000-\u001f\u007f\\]/.test(logo)) return undefined;
  if (/^\/(?!\/)/.test(logo)) return logo;
  try {
    const url = new URL(logo);
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? logo : undefined;
  } catch {return undefined;}
}
export const tenantEndpoint = (directory: TenantDirectory) => directory === 'platform'
  ? '/common/platform/tenants' : '/common/current-directory/tenants';

export function tenantOption(row: Record<string, unknown>, unnamed: string, platform: string, personal?: string): EntityOption | undefined {
  const readable = (value: unknown) => typeof value === 'string' && value.trim() && !/^\*+$/.test(value.trim());
  if (!readable(row.id)) return undefined;
  // Platform CRUD uses tenantType, while the minimal current directory uses type.
  const type = tenantType(row.tenantType ?? row.type);
  return {value: row.id as string, label: type === 'PLATFORM' ? platform
    : type === 'PERSONAL' && personal ? personal : readable(row.name) ? (row.name as string).trim() : unnamed,
    metadata: {type, logo: tenantLogo(row.logo)}};
}
