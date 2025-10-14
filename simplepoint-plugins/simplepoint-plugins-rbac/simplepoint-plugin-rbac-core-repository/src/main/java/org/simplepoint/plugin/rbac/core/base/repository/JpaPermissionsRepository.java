/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.base.repository;

import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.core.api.repository.PermissionsRepository;
import org.simplepoint.security.entity.Permissions;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for managing Permissions entities.
 * This interface extends BaseRepository and provides additional custom query methods
 * for retrieving permissions associated with specific roles.
 */
@Repository
public interface JpaPermissionsRepository extends BaseRepository<Permissions, String>,
    PermissionsRepository {
}
