package org.simplepoint.plugin.rbac.core.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.security.entity.Role;

/**
 * RolesRepository provides an interface for managing Role entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for Role entities.
 */
public interface RoleRepository extends BaseRepository<Role, String> {
}
