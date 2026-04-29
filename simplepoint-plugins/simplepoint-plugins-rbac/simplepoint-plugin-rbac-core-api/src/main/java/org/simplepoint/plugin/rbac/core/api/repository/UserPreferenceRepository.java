/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.security.entity.UserPreference;

/**
 * Repository interface for managing UserPreference entities.
 */
public interface UserPreferenceRepository extends BaseRepository<UserPreference, String> {

  /**
   * Finds a preference by user ID and preference key.
   *
   * @param userId        the user's ID
   * @param preferenceKey the preference key
   * @return an Optional containing the preference if found
   */
  Optional<UserPreference> findByUserIdAndPreferenceKey(String userId, String preferenceKey);

  /**
   * Deletes a preference by user ID and preference key.
   *
   * @param userId        the user's ID
   * @param preferenceKey the preference key
   */
  void deleteByUserIdAndPreferenceKey(String userId, String preferenceKey);
}
