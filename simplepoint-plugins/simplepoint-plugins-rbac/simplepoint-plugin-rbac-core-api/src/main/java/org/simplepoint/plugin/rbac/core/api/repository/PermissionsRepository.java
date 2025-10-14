package org.simplepoint.plugin.rbac.core.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.security.entity.Permissions;

/**
 * PermissionsRepositoryApi provides an interface for managing Permissions entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for Permissions entities.
 */
public interface PermissionsRepository extends BaseRepository<Permissions, String> {
}
