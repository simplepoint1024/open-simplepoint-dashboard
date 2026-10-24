import TenantPage from './TenantPage';
import {TenantResourceManager} from './TenantResourceManager';
import TenantPackages from './packages';

/** Resource CRUD pages use the same bounded host content frame as other platform lists. */
export function TenantResourcesPage() {
  return <TenantPage capability="resourceManageable">{profile => <div className="tenant-resource-page">
    <TenantResourceManager tenantId={profile.id}/>
  </div>}</TenantPage>;
}

export function TenantPackagesPage() {
  return <TenantPage capability="packageManageable">{profile => <div className="tenant-package-page">
    <TenantPackages key={profile.id}/>
  </div>}</TenantPage>;
}
