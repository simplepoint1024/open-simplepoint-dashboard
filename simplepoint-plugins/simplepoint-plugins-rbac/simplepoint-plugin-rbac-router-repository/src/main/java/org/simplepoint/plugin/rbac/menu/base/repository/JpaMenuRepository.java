/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.menu.base.repository;

import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuRepository;
import org.simplepoint.security.entity.Menu;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for managing Menu entities.
 * This interface extends BaseRepository to provide CRUD operations
 * for Menu entities.
 */
@Repository
public interface JpaMenuRepository extends BaseRepository<Menu, String>, MenuRepository {
}

