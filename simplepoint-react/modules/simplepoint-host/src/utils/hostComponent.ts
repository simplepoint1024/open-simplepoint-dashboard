/** Host-owned pages are resolved locally; they must never enter remote prefetch. */
const hostComponents = {
  'host/TenantResources': 'resources',
  'host/TenantPackages': 'packages',
  'host/TenantSettings': 'settings',
  'host/TenantHome': 'settings',
} as const;

export function resolveHostComponent(spec?: string, path?: string): 'resources' | 'packages' | 'settings' | undefined {
  if (spec === 'host/TenantHome') {
    if (path === '/tenant/resources') return 'resources';
    if (path === '/tenant/packages') return 'packages';
  }
  return spec && Object.prototype.hasOwnProperty.call(hostComponents, spec)
    ? hostComponents[spec as keyof typeof hostComponents]
    : undefined;
}
