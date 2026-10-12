export type UserIdentity = {
  name?: string | null; email?: string | null;
  familyName?: string | null; middleName?: string | null; givenName?: string | null;
  family_name?: string | null; middle_name?: string | null; given_name?: string | null;
};

const text = (value: unknown) => typeof value === 'string' ? value.trim() || undefined : undefined;

/** Explicit full name wins; legacy components retain the platform's family/middle/given order. */
export function userFullName(user?: UserIdentity | null): string | undefined {
  if (!user) return undefined;
  const parts = ['family', 'middle', 'given'].map(part => {
    const camel = `${part}Name` as keyof UserIdentity, oidc = `${part}_name` as keyof UserIdentity;
    // An explicit clear from the profile response overrides an older OIDC claim in the cache.
    return text(camel in user ? user[camel] : user[oidc]);
  }).filter(Boolean).join(' ');
  return text(user.name) || text(parts);
}

/** Labels never fall back to nicknames, phone numbers or internal account identifiers. */
export function userDisplayName(user?: UserIdentity | null): string | undefined {
  return userFullName(user) || text(user?.email);
}
