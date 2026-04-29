/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.base.repository;

import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.core.api.repository.UserPreferenceRepository;
import org.simplepoint.security.entity.UserPreference;
import org.springframework.stereotype.Repository;

/**
 * JPA implementation of UserPreferenceRepository.
 */
@Repository
public interface JpaUserPreferenceRepository
    extends BaseRepository<UserPreference, String>, UserPreferenceRepository {

}
