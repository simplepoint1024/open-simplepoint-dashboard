/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.base.repository;

import org.simplepoint.plugin.rbac.core.api.repository.FieldScopeRepository;
import org.simplepoint.security.entity.FieldScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA implementation of {@link FieldScopeRepository}.
 */
@Repository
public interface JpaFieldScopeRepository extends JpaRepository<FieldScope, String>, FieldScopeRepository {
}
