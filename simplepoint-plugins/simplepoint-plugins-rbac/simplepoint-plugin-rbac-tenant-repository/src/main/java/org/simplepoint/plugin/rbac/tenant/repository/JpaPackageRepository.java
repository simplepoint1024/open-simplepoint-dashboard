package org.simplepoint.plugin.rbac.tenant.repository;

import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Package;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageRepository;
import org.springframework.stereotype.Repository;

/**
 * JpaPackageRepository provides an interface for managing Package entities.
 * It extends the BaseRepository to inherit basic CRUD operations and the PackageRepository
 * to include specific methods for handling package data.
 */
@Repository
public interface JpaPackageRepository extends BaseRepository<Package, String>, PackageRepository {
}
