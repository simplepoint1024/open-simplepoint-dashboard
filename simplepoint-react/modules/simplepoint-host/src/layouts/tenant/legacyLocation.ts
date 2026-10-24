/** Old settings tabs resolve to their independently authorized page identities. */
export function legacyTenantTarget(location: string): string | undefined {
  const url = new URL(location, 'https://workspace.invalid');
  if (url.pathname !== '/tenant') return undefined;
  const section = url.searchParams.get('section');
  if (section === 'resources' || section === 'packages' || section === 'settings') return `/tenant/${section}`;
  if (url.searchParams.get('edit') === '1') return '/tenant/settings';
  return undefined;
}
