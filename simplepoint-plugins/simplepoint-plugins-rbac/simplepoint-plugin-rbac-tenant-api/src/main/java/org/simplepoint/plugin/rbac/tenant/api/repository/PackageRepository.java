package org.simplepoint.plugin.rbac.tenant.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Package;

/**
 * PackageRepository provides an interface for managing Package entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 */
public interface PackageRepository extends BaseRepository<Package, String> {
}
