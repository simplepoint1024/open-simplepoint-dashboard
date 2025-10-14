/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.base.repository;

import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.security.entity.Role;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for managing Role entities.
 *
 * <p>The relationship between roles and permissions in the RBAC (Role-Based Access Control) system
 * is many-to-many, meaning multiple roles can have multiple permissions associated with them.
 * This interface extends BaseRepository and provides basic CRUD functionality for roles.
 */
@Repository
public interface JpaRolesRepository extends BaseRepository<Role, String>, RoleRepository {
}
