export type TenantPackage = {
  id: string;
  packageCode?: string;
  packageName?: string;
  durationDays?: number | null;
  state: string;
  acquisition: string;
  allowedActions?: string[];
};
export type PackageResource = {
  id: string;
  resourceCode?: string;
  resourceName?: string;
  durationDays?: number | null;
  activatedAt?: string;
  creditedExpiresAt?: string;
  revokedAt?: string;
  currentStartsAt?: string;
  currentExpiresAt?: string;
  currentState: string;
};
